package labs.database.distributed_db_tidb_oceanbase;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * 不自己拆，换个数据库：在本机的 TiDB 8.5.8 小集群（1 PD + 3 TiKV + 2 TiDB）上看它替你做了什么。
 *
 * <pre>
 *   tour      建表、灌 2 万单；SHOW TABLE … REGIONS 看数据被切成几块、每块的 3 个副本和 leader 在哪台 TiKV；
 *             SPLIT TABLE 切成 4 块后 leader 怎么分布；EXPLAIN ANALYZE 看 COUNT 被下推到 TiKV；事务的开始时间戳来自 PD 的 TSO
 *   failover  一个线程不停地单行写入，第 5 秒 kill 掉一台 TiKV：写入卡了多久、失败几次、什么时候恢复，leader 搬到了哪
 * </pre>
 * 先跑 cluster/cluster.sh up。连接 tidb1：127.0.0.1:4001，MySQL 协议，root 无密码。
 */
public class TidbLab {

    static final String URL = "jdbc:mysql://127.0.0.1:4001/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=30000";
    static final String DOCKER = System.getenv().getOrDefault("DOCKER", "docker");

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        if (mode.equals("all") || mode.equals("tour")) tour();
        if (mode.equals("all") || mode.equals("failover")) failover(false);
        if (mode.equals("all") || mode.equals("failover-leader")) failover(true);
    }

    static final String REGIONS = "SELECT r.REGION_ID, r.START_KEY, r.END_KEY, r.APPROXIMATE_KEYS AS keys_approx,"
                    + " (SELECT STORE_ID FROM information_schema.TIKV_REGION_PEERS p WHERE p.REGION_ID = r.REGION_ID AND p.IS_LEADER = 1) AS leader_store,"
                    + " (SELECT GROUP_CONCAT(STORE_ID ORDER BY STORE_ID) FROM information_schema.TIKV_REGION_PEERS p WHERE p.REGION_ID = r.REGION_ID) AS replica_stores"
                    + " FROM information_schema.TIKV_REGION_STATUS r WHERE r.DB_NAME = 'trade' AND r.TABLE_NAME = 't_order' AND r.IS_INDEX = 0 ORDER BY r.START_KEY";

    static Connection conn(String db) throws SQLException { return DriverManager.getConnection(String.format(URL, db), "root", ""); }

    static void title(String t) { System.out.println(); System.out.println("==== " + t); }

    static void print(Connection c, String sql, int maxRows, int width) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = 0;
            while (rs.next() && n++ < maxRows) {
                StringBuilder sb = new StringBuilder("   ");
                for (int i = 1; i <= md.getColumnCount(); i++) sb.append(i == 1 ? "" : " | ").append(rs.getString(i));
                String s = sb.toString();
                System.out.println(s.length() > width ? s.substring(0, width) + " …" : s);
            }
        }
    }

    // ---------------------------------------------------------------- tour
    static void tour() throws Exception {
        try (Connection c = conn("")) {
            try (Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS trade");
                st.execute("CREATE DATABASE trade");
                st.execute("USE trade");
                st.execute("CREATE TABLE t_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL, amount_cents BIGINT NOT NULL, KEY idx_user (user_id))");
                st.execute("SET SESSION cte_max_recursion_depth = 30000");
                st.execute("INSERT INTO t_order WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 20000)"
                        + " SELECT n, 1000 + n % 5000, 301 + n % 50, 'PAID', 1000 + (n * 13) % 90000 FROM s");
            }
            title("tour 1：3 台 TiKV（store）");
            print(c, "SELECT STORE_ID, ADDRESS, STORE_STATE_NAME, LEADER_COUNT, REGION_COUNT FROM information_schema.TIKV_STORE_STATUS ORDER BY STORE_ID", 10, 160);
            title("tour 2：t_order 2 万行，现在是几个 Region（数据块），每块的 leader 和副本在哪");
            print(c, REGIONS, 10, 220);
            try (Statement st = c.createStatement()) {
                st.execute("SPLIT TABLE trade.t_order BETWEEN (0) AND (20000) REGIONS 4");
            }
            Thread.sleep(3000);
            title("tour 3：SPLIT TABLE … REGIONS 4 以后（按主键切 4 段，PD 会把 leader 分散到不同的 store）");
            print(c, REGIONS, 10, 220);
            title("tour 4：EXPLAIN ANALYZE：COUNT 被拆到每个 Region 上先算（cop[tikv]），TiDB 再把几个部分和加起来");
            print(c, "EXPLAIN ANALYZE SELECT COUNT(*) FROM trade.t_order WHERE shop_id = 301", 10, 200);
            title("tour 5：事务的开始时间戳来自 PD 的 TSO");
            try (Statement st = c.createStatement()) {
                st.execute("BEGIN");
                print(c, "SELECT @@tidb_current_ts AS start_ts, TIDB_PARSE_TSO(@@tidb_current_ts) AS physical_time", 1, 200);
                st.execute("COMMIT");
            }
        }
    }

    // ---------------------------------------------------------------- failover
    /** leader=false：杀一台不持有 t_write 的 leader 的 TiKV；leader=true：专门杀持有它 leader 的那台。 */
    static void failover(boolean leader) throws Exception {
        String victim = null;
        try (Connection c = conn("trade"); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t_write");
            st.execute("CREATE TABLE t_write (id BIGINT PRIMARY KEY, ts BIGINT)");
            st.execute("INSERT INTO t_write VALUES (0, 0)");
            Thread.sleep(2000);
            try (ResultSet rs = st.executeQuery("SELECT s.ADDRESS, p.IS_LEADER FROM information_schema.TIKV_REGION_STATUS r"
                    + " JOIN information_schema.TIKV_REGION_PEERS p ON p.REGION_ID = r.REGION_ID"
                    + " JOIN information_schema.TIKV_STORE_STATUS s ON s.STORE_ID = p.STORE_ID"
                    + " WHERE r.DB_NAME = 'trade' AND r.TABLE_NAME = 't_write' AND r.IS_INDEX = 0 ORDER BY p.IS_LEADER DESC")) {
                while (rs.next()) {
                    String host = rs.getString(1).split(":")[0];
                    boolean isLeader = rs.getInt(2) == 1;
                    if (leader == isLeader && victim == null) victim = host;
                    System.out.println("   t_write 的副本：" + host + (isLeader ? "（leader）" : "（follower）"));
                }
            }
        }
        if (victim == null) throw new IllegalStateException("没找到要杀的 TiKV");
        AtomicBoolean run = new AtomicBoolean(true);
        AtomicLong ok = new AtomicLong(), fail = new AtomicLong();
        List<long[]> log = Collections.synchronizedList(new ArrayList<>());   // {秒, 本次耗时 ms, 成功?1:0}
        long t0 = System.currentTimeMillis();
        Thread w = new Thread(() -> {
            long id = 1_000_000L * (leader ? 2 : 1);
            Connection c = null;
            while (run.get()) {
                long s = System.currentTimeMillis();
                try {
                    if (c == null) c = conn("trade");
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_write VALUES (?, ?)")) {
                        ps.setLong(1, id++);
                        ps.setLong(2, s);
                        ps.executeUpdate();
                    }
                    ok.incrementAndGet();
                    log.add(new long[]{(s - t0) / 1000, System.currentTimeMillis() - s, 1});
                } catch (SQLException e) {
                    fail.incrementAndGet();
                    log.add(new long[]{(s - t0) / 1000, System.currentTimeMillis() - s, 0});
                    try { if (c != null) c.close(); } catch (SQLException ignore) { }
                    c = null;
                }
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        });
        w.start();
        Thread.sleep(5000);
        long killAt = System.currentTimeMillis();
        new ProcessBuilder((DOCKER + " kill " + victim).split(" ")).inheritIO().start().waitFor();
        Thread.sleep(35_000);
        run.set(false);
        w.join();
        title("failover：每 20 ms 写一行，第 " + (killAt - t0) / 1000 + " 秒 kill -9 掉 " + victim + (leader ? "（t_write 的 leader 在它上面）" : "（它只有 t_write 的 follower）"));
        System.out.println("   秒  成功  失败  这一秒最慢(ms)");
        Map<Long, long[]> per = new TreeMap<>();
        synchronized (log) {
            for (long[] x : log) {
                long[] a = per.computeIfAbsent(x[0], k -> new long[3]);
                if (x[2] == 1) a[0]++; else a[1]++;
                a[2] = Math.max(a[2], x[1]);
            }
        }
        per.forEach((sec, a) -> System.out.printf("   %2d  %4d  %4d  %6d%n", sec, a[0], a[1], a[2]));
        long maxGap = 0;
        synchronized (log) {
            for (long[] x : log) maxGap = Math.max(maxGap, x[1]);       // 最长一次写入耗时，就是“卡住”的时长
        }
        System.out.println("   合计成功 " + ok.get() + "，失败 " + fail.get() + "；单次写入最长 " + maxGap + " ms");
        try (Connection c = conn("trade")) {
            title("failover 之后的 store 状态");
            print(c, "SELECT STORE_ID, ADDRESS, STORE_STATE_NAME, LEADER_COUNT, REGION_COUNT FROM information_schema.TIKV_STORE_STATUS ORDER BY STORE_ID", 10, 160);
        }
        new ProcessBuilder((DOCKER + " start " + victim).split(" ")).inheritIO().start().waitFor();
        System.out.println("   （已重新启动 " + victim + "）");
    }
}
