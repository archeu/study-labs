package labs.database.online_data_migration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 不停机迁移演练：旧表 order_db.t_order（20 万行）→ 新的 2 库 8 表，迁移期间下单、改状态的流量一直在跑。
 *
 * <pre>
 *   naive  双写不带版本保护、搬运用 upsert 覆盖：看校验能抓到多少行状态倒退
 *   safe   双写带版本保护（新版本才覆盖）、搬运用 INSERT IGNORE（已有就不动）：同样的流量下再比一次
 * </pre>
 * 两种模式都注入同样的“新库写失败”（千分之二，只记日志、不重放），由分块校验找出来、以旧表为准修复，
 * 最后按用户灰度 10% 切读并对比新旧结果，再把开关拨回去（回滚）。
 */
public class MigrationLab {

    static final int ROWS = 200_000, BATCH = 5_000, CHUNK = 10_000;
    static final long NEW_ID_START = 10_000_000L;
    static final String[] FLOW = {"CREATED", "PAID", "SHIPPED", "DONE"};

    static HikariDataSource old;
    static DataSource neu;
    static final Map<String, HikariDataSource> RAW = new HashMap<>();
    static long t0;

    static void log(String fmt, Object... a) {
        System.out.printf("[%6.2f s] " + fmt + "%n", prepend((System.nanoTime() - t0) / 1e9, a));
    }

    static Object[] prepend(Object x, Object[] a) {
        Object[] r = new Object[a.length + 1];
        r[0] = x;
        System.arraycopy(a, 0, r, 1, a.length);
        return r;
    }

    public static void main(String[] args) throws Exception {
        old = pool("order_db", 24);
        RAW.put("ds_0", pool("ds_0", 4));
        RAW.put("ds_1", pool("ds_1", 4));
        List<String> modes = args.length > 0 && !args[0].equals("all") ? List.of(args) : List.of("naive", "safe");
        for (String m : modes) drill(m.equals("safe"));
        if (neu instanceof AutoCloseable ac) ac.close();
        old.close();
        RAW.values().forEach(HikariDataSource::close);
    }

    static HikariDataSource pool(String db, int size) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl("jdbc:mysql://127.0.0.1:33113/" + db + "?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true");
        c.setUsername("root");
        c.setPassword("root");
        c.setMaximumPoolSize(size);
        return new HikariDataSource(c);
    }

    // ------------------------------------------------------------------ 演练
    static void drill(boolean safe) throws Exception {
        t0 = System.nanoTime();
        System.out.println();
        System.out.println("==================== " + (safe ? "safe：双写带版本保护 + 搬运 INSERT IGNORE" : "naive：双写直接覆盖 + 搬运 upsert 覆盖"));
        init();
        // ShardingSphere 启动时加载真实表的元数据，所以新表建好以后再创建它
        if (neu instanceof AutoCloseable ac) ac.close();
        try (InputStream in = MigrationLab.class.getResourceAsStream("/target.yaml")) {
            neu = YamlShardingSphereDataSourceFactory.createDataSource(in.readAllBytes());
        }
        log("D-14 之前：旧表 %d 行，新表 8 张、空", ROWS);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong updates = new AtomicLong(), inserts = new AtomicLong(), dualFail = new AtomicLong();
        AtomicLong nextId = new AtomicLong(NEW_ID_START);
        ExecutorService traffic = Executors.newFixedThreadPool(5);
        for (int t = 0; t < 4; t++) traffic.submit(() -> { updater(safe, running, updates, dualFail); return null; });
        traffic.submit(() -> { inserter(running, nextId, inserts, dualFail); return null; });
        log("D-14：双写开关打开（旧表为主），4 个线程改状态、1 个线程下新单");

        long b0 = System.nanoTime();
        try {
            backfill(safe);
        } catch (Exception e) {
            running.set(false);
            traffic.shutdownNow();
            throw e;
        }
        double secs = (System.nanoTime() - b0) / 1e9;
        log("搬运完成：%d 行，%.1f s，%.0f 行/秒（每批 %d 行，批间停 30 ms 限速）", ROWS, secs, ROWS / secs, BATCH);

        Thread.sleep(1000);
        running.set(false);
        traffic.shutdown();
        traffic.awaitTermination(30, TimeUnit.SECONDS);
        log("停流量：迁移期间改状态 %d 次、下新单 %d 单、新库写失败 %d 次（只记日志，不重放）", updates.get(), inserts.get(), dualFail.get());

        long maxId = nextId.get();
        Verify v1 = verify(maxId);
        log("第 1 次分块校验：%d 块里 %d 块对不上", v1.chunks, v1.badChunks.size());
        Diff d = diff(v1.badChunks, maxId);
        log("逐行比对：缺行 %d，状态倒退（新表版本更旧）%d，其他不一致 %d", d.missing, d.stale, d.other);
        int fixed = repair(d.rows);
        log("以旧表为准修复 %d 行", fixed);
        Verify v2 = verify(maxId);
        log("第 2 次分块校验：%d 块里 %d 块对不上", v2.chunks, v2.badChunks.size());

        grayRead(10, 2000);
        log("回滚演练：读开关拨回 0%%，所有读回到旧表；旧表一直是主，数据完整");
    }

    // ------------------------------------------------------------------ 准备数据
    static void init() throws SQLException {
        try (Connection c = old.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t_order");
            st.execute("CREATE TABLE t_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
                    + " version INT NOT NULL, amount_cents BIGINT NOT NULL, updated_at DATETIME(3) NOT NULL, KEY idx_user (user_id))");
            st.execute("SET SESSION cte_max_recursion_depth = 300000");
            st.execute("INSERT INTO t_order WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < " + ROWS + ")"
                    + " SELECT n, 1000 + (n * 7919) % 50000, 'CREATED', 1, 1000 + (n * 13) % 90000, NOW(3) FROM s");
        }
        for (int k = 0; k < 8; k++) {
            try (Connection c = RAW.get("ds_" + (k % 2)).getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS t_order_" + k);
                st.execute("CREATE TABLE t_order_" + k + " (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
                        + " version INT NOT NULL, amount_cents BIGINT NOT NULL, updated_at DATETIME(3) NOT NULL, KEY idx_user (user_id))");
            }
        }
    }

    // ------------------------------------------------------------------ 流量：改状态（双写）
    static final String UPSERT_PLAIN = "INSERT INTO t_order (id, user_id, status, version, amount_cents, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE status = VALUES(status), version = VALUES(version), updated_at = VALUES(updated_at)";
    // 版本保护：只有更新的版本才覆盖；version 必须最后改，前面几列比较的还是旧 version
    static final String UPSERT_GUARD = "INSERT INTO t_order (id, user_id, status, version, amount_cents, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE status = IF(VALUES(version) > version, VALUES(status), status),"
            + " updated_at = IF(VALUES(version) > version, VALUES(updated_at), updated_at),"
            + " version = GREATEST(version, VALUES(version))";

    static void updater(boolean safe, AtomicBoolean running, AtomicLong updates, AtomicLong dualFail) throws Exception {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (running.get()) {
            long id = r.nextInt(10) < 3 ? 1 + r.nextInt(200) : 1 + r.nextInt(ROWS);   // 30% 打在 200 个热点订单上
            Object[] row;
            try (Connection c = old.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("SELECT user_id, status, version, amount_cents FROM t_order WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); continue; }
                        int ver = rs.getInt(3) + 1;
                        String st = FLOW[ver % FLOW.length];
                        Timestamp now = new Timestamp(System.currentTimeMillis());
                        row = new Object[]{id, rs.getLong(1), st, ver, rs.getLong(4), now};
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE t_order SET status = ?, version = ?, updated_at = ? WHERE id = ?")) {
                    ps.setString(1, (String) row[2]);
                    ps.setInt(2, (Integer) row[3]);
                    ps.setTimestamp(3, (Timestamp) row[5]);
                    ps.setLong(4, id);
                    ps.executeUpdate();
                }
                c.commit();
            }
            updates.incrementAndGet();
            Thread.sleep(0, r.nextInt(1_000_000));        // 旧库提交后、写新库前：网络抖动 0～1 ms
            if (r.nextInt(1000) < 2) { dualFail.incrementAndGet(); continue; }   // 新库写失败，只记日志
            try (Connection c = neu.getConnection(); PreparedStatement ps = c.prepareStatement(safe ? UPSERT_GUARD : UPSERT_PLAIN)) {
                for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                ps.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ 流量：下新单（双写）
    static void inserter(AtomicBoolean running, AtomicLong nextId, AtomicLong inserts, AtomicLong dualFail) throws Exception {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (running.get()) {
            long id = nextId.getAndIncrement();
            Object[] row = {id, 1000L + r.nextInt(50000), "CREATED", 1, 1000L + r.nextInt(90000), new Timestamp(System.currentTimeMillis())};
            try (Connection c = old.getConnection(); PreparedStatement ps = c.prepareStatement("INSERT INTO t_order VALUES (?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                ps.executeUpdate();
            }
            inserts.incrementAndGet();
            if (r.nextInt(1000) < 2) { dualFail.incrementAndGet(); continue; }
            try (Connection c = neu.getConnection(); PreparedStatement ps = c.prepareStatement(UPSERT_GUARD)) {
                for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                ps.executeUpdate();
            }
            Thread.sleep(2);
        }
    }

    // ------------------------------------------------------------------ 搬运
    static void backfill(boolean safe) throws Exception {
        String sql = safe
                ? "INSERT IGNORE INTO t_order (id, user_id, status, version, amount_cents, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                : UPSERT_PLAIN;
        for (long from = 1; from <= ROWS; from += BATCH) {
            List<Object[]> rows = new ArrayList<>(BATCH);
            try (Connection c = old.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT id, user_id, status, version, amount_cents, updated_at FROM t_order WHERE id BETWEEN ? AND ?")) {
                ps.setLong(1, from);
                ps.setLong(2, from + BATCH - 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[]{rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getLong(5), rs.getTimestamp(6)});
                }
            }
            // 读出来到写进去之间：这一批在应用内存里待一会儿（转换、限速），旧表上的这些行可能已经被改了
            try (Connection c = neu.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                c.setAutoCommit(false);
                for (Object[] row : rows) {
                    for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            }
            Thread.sleep(30);
        }
    }

    // ------------------------------------------------------------------ 分块校验
    record Verify(int chunks, List<long[]> badChunks) {}

    static final String SUM = "SELECT COUNT(*), COALESCE(BIT_XOR(CRC32(CONCAT_WS('#', id, user_id, status, version, amount_cents))), 0)"
            + " FROM %s WHERE id BETWEEN ? AND ?";

    static List<long[]> chunks(long maxId) {
        List<long[]> r = new ArrayList<>();
        for (long f = 1; f <= ROWS; f += CHUNK) r.add(new long[]{f, f + CHUNK - 1});
        for (long f = NEW_ID_START; f < maxId; f += CHUNK) r.add(new long[]{f, f + CHUNK - 1});
        return r;
    }

    static long[] sum(Connection c, String table, long[] ch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(String.format(SUM, table))) {
            ps.setLong(1, ch[0]);
            ps.setLong(2, ch[1]);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return new long[]{rs.getLong(1), rs.getLong(2)}; }
        }
    }

    static Verify verify(long maxId) throws SQLException {
        List<long[]> bad = new ArrayList<>();
        List<long[]> all = chunks(maxId);
        try (Connection o = old.getConnection(); Connection d0 = RAW.get("ds_0").getConnection(); Connection d1 = RAW.get("ds_1").getConnection()) {
            for (long[] ch : all) {
                long[] a = sum(o, "t_order", ch);
                long cnt = 0, xor = 0;
                for (int k = 0; k < 8; k++) {           // 新库的 8 张表各算一份，行数相加、校验和异或：两边可以直接比
                    long[] b = sum(k % 2 == 0 ? d0 : d1, "t_order_" + k, ch);
                    cnt += b[0];
                    xor ^= b[1];
                }
                if (a[0] != cnt || a[1] != xor) bad.add(ch);
            }
        }
        return new Verify(all.size(), bad);
    }

    // ------------------------------------------------------------------ 逐行比对、修复
    record Diff(int missing, int stale, int other, List<Long> rows) {}

    static Map<Long, Object[]> load(Connection c, String table, long[] ch) throws SQLException {
        Map<Long, Object[]> m = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, user_id, status, version, amount_cents FROM " + table + " WHERE id BETWEEN ? AND ?")) {
            ps.setLong(1, ch[0]);
            ps.setLong(2, ch[1]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.put(rs.getLong(1), new Object[]{rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getLong(5)});
            }
        }
        return m;
    }

    static Diff diff(List<long[]> bad, long maxId) throws SQLException {
        int missing = 0, stale = 0, other = 0;
        List<Long> rows = new ArrayList<>();
        try (Connection o = old.getConnection(); Connection d0 = RAW.get("ds_0").getConnection(); Connection d1 = RAW.get("ds_1").getConnection()) {
            for (long[] ch : bad) {
                Map<Long, Object[]> a = load(o, "t_order", ch), b = new HashMap<>();
                for (int k = 0; k < 8; k++) b.putAll(load(k % 2 == 0 ? d0 : d1, "t_order_" + k, ch));
                for (Map.Entry<Long, Object[]> e : a.entrySet()) {
                    Object[] x = e.getValue(), y = b.get(e.getKey());
                    if (y == null) { missing++; rows.add(e.getKey()); }
                    else if (!Arrays.equals(x, y)) {
                        if ((Integer) y[2] < (Integer) x[2]) stale++; else other++;
                        rows.add(e.getKey());
                    }
                }
            }
        }
        return new Diff(missing, stale, other, rows);
    }

    static int repair(List<Long> ids) throws SQLException {
        int n = 0;
        try (Connection o = old.getConnection(); Connection c = neu.getConnection();
             PreparedStatement rd = o.prepareStatement("SELECT id, user_id, status, version, amount_cents, updated_at FROM t_order WHERE id = ?");
             PreparedStatement wr = c.prepareStatement(UPSERT_PLAIN)) {   // 流量已停，以旧表为准直接覆盖
            for (long id : ids) {
                rd.setLong(1, id);
                try (ResultSet rs = rd.executeQuery()) {
                    if (!rs.next()) continue;
                    for (int i = 1; i <= 6; i++) wr.setObject(i, rs.getObject(i));
                }
                wr.executeUpdate();
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 灰度切读 + 对比
    static void grayRead(int percent, int reads) throws SQLException {
        int toNew = 0, mismatch = 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        try (Connection o = old.getConnection(); Connection c = neu.getConnection();
             PreparedStatement po = o.prepareStatement("SELECT id, status, version FROM t_order WHERE user_id = ? ORDER BY id");
             PreparedStatement pn = c.prepareStatement("SELECT id, status, version FROM t_order WHERE user_id = ? ORDER BY id")) {
            for (int i = 0; i < reads; i++) {
                long user = 1000 + r.nextInt(50000);
                if (user % 100 >= percent) continue;          // 灰度：user_id % 100 < 10 的用户读新表
                toNew++;
                if (!rows(po, user).equals(rows(pn, user))) mismatch++;   // 影子对比：同时读一次旧表
            }
        }
        log("切读 %d%%：%d 次读里 %d 次落到新表，和旧表对比不一致 %d 次", percent, reads, toNew, mismatch);
    }

    static List<String> rows(PreparedStatement ps, long user) throws SQLException {
        ps.setLong(1, user);
        List<String> l = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) l.add(rs.getLong(1) + ":" + rs.getString(2) + ":" + rs.getInt(3));
        }
        return l;
    }
}
