package labs.arch.distributed_id;

import com.baomidou.mybatisplus.core.toolkit.Sequence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * 交易靶场 J09 全景“一次下单跨三个库”的第 2 站：订单服务发出订单号 9527。
 * 三段：segment（号段）、snowflake（雪花）、btree（不同 ID 插进 InnoDB 的代价）。
 */
public class IdLab {
    static final String ROOT = "jdbc:mysql://127.0.0.1:33097/?useSSL=false&allowPublicKeyRetrieval=true";
    static final String URL = "jdbc:mysql://127.0.0.1:33097/idlab?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";

    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "all";
        try (Connection c = DriverManager.getConnection(ROOT, "root", "root"); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS idlab");
        }
        if (what.equals("segment") || what.equals("all")) segment();
        if (what.equals("snowflake") || what.equals("all")) snowflake();
        if (what.equals("btree") || what.equals("all")) btree();
    }

    // ------------------------------------------------------------------ 号段

    static void resetAlloc() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "root", "root"); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS id_alloc");
            s.execute("CREATE TABLE id_alloc (biz_tag VARCHAR(32) PRIMARY KEY, max_id BIGINT NOT NULL, step INT NOT NULL,"
                    + " update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO id_alloc (biz_tag, max_id, step) VALUES ('order', 9000, 1000), ('order_bulk', 0, 1000),"
                    + " ('order_slow', 0, 1000), ('order_down', 0, 1000)");
        }
    }

    static void segment() throws Exception {
        resetAlloc();
        System.out.println("== segment ①：两个订单服务实例各领一段（id_alloc 里 order 的 max_id 从 9000 开始，step 1000）");
        SegmentIdGen a = new SegmentIdGen("A", URL, "order", true);
        SegmentIdGen b = new SegmentIdGen("B", URL, "order", true);
        System.out.println("A 启动领到 " + a.current() + "，B 启动领到 " + b.current());
        for (int i = 0; i < 526; i++) a.nextId();
        System.out.println("A 发到第 527 个号：订单号 " + a.nextId() + "（全程没访问库，A 的 id_alloc 访问次数 = 1 + 预取 "
                + (a.dbCalls.get() - 1) + "）");
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < 3; i++) { seq.append("A ").append(a.nextId()).append(" → "); seq.append("B ").append(b.nextId()).append(" → "); }
        System.out.println("接下来按时间先后交替下 6 单：" + seq.substring(0, seq.length() - 3));
        Thread.sleep(100);                                    // 等 A 的预取落地
        long lastA = a.current().next.get() - 1;
        long lost = a.current().max - lastA;
        String pre = a.nxtString();
        if (pre != null) lost += 1000;
        System.out.println("A 被 kill -9：当前段发到 " + lastA + "，剩下 " + (a.current().max - lastA) + " 个"
                + (pre != null ? "，预取好的下一段 " + pre + " 也一起丢了" : ""));
        a.close();
        SegmentIdGen a2 = new SegmentIdGen("A'", URL, "order", true);
        System.out.println("A 重启领到 " + a2.current() + "：永远不会再发出来的号 " + lost + " 个（订单号有空洞，但没有重复）");
        a2.close(); b.close();

        System.out.println("\n== segment ②：两个实例 × 4 个线程，各发 50,000 个号（双 buffer），查重");
        SegmentIdGen x = new SegmentIdGen("A", URL, "order_bulk", true);
        SegmentIdGen y = new SegmentIdGen("B", URL, "order_bulk", true);
        final long[][] out = new long[8][50_000];
        List<Thread> ts = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int t = 0; t < 8; t++) {
            final int ti = t;
            final SegmentIdGen g = t < 4 ? x : y;
            Thread th = new Thread(() -> {
                try { for (int i = 0; i < out[ti].length; i++) out[ti][i] = g.nextId(); } catch (SQLException e) { throw new RuntimeException(e); }
            });
            ts.add(th); th.start();
        }
        for (Thread th : ts) th.join();
        double secs = (System.nanoTime() - t0) / 1e9;
        long[] all = Arrays.stream(out).flatMapToLong(Arrays::stream).toArray();
        Arrays.sort(all);
        int dup = 0;
        for (int i = 1; i < all.length; i++) if (all[i] == all[i - 1]) dup++;
        System.out.printf("发了 %,d 个号，用时 %.2f s（%,.0f 个/秒），重复 %d 个，访问 id_alloc %d 次（一次领 1000 个）%n",
                all.length, secs, all.length / secs, dup, x.dbCalls.get() + y.dbCalls.get());
        x.close(); y.close();

        System.out.println("\n== segment ③：领号段时库慢了 200 ms（DO SLEEP），按固定速率发 3 秒，看每次 nextId() 的耗时");
        System.out.println("写法            | 速率      | 一段能用多久 | 发了多少个 | 访问库 | 最慢一次 | 超过 50 ms 的调用");
        paced("单 buffer", false, 4000);
        paced("双 buffer", true, 4000);
        paced("双 buffer", true, 8000);

        System.out.println("\n== segment ④：双 buffer，速率 4000 个/秒，发到第 200 个时 id_alloc 所在的库挂了（用开关模拟）");
        SegmentIdGen d = new SegmentIdGen("A", URL, "order_down", true);
        long period = 1_000_000_000L / 4000;
        long start = System.nanoTime();
        int n = 0;
        for (; n < 200; n++) { LockSupport.parkNanos(start + n * period - System.nanoTime()); d.nextId(); }
        Thread.sleep(20);
        System.out.println("挂之前：当前段 " + d.current() + "，预取好的下一段 " + d.nxtString());
        d.dbDown = true;
        long downAt = System.nanoTime();
        int after = 0;
        try {
            while (true) { LockSupport.parkNanos(start + n * period - System.nanoTime()); d.nextId(); n++; after++; }
        } catch (IllegalStateException e) {
            System.out.printf("库挂了之后又发了 %d 个号，撑了 %.0f ms，然后：%s（这期间访问库失败 %d 次）%n",
                    after, (System.nanoTime() - downAt) / 1e6, e.getMessage(), d.dbCalls.get() - 2);
        }
        d.close();
    }

    static void paced(String label, boolean dbl, int rate) throws Exception {
        SegmentIdGen g = new SegmentIdGen("A", URL, "order_slow", dbl);
        g.dbDelayMs = 200;
        int calls0 = g.dbCalls.get();
        long period = 1_000_000_000L / rate;
        long start = System.nanoTime(), end = start + 3_000_000_000L;
        long max = 0; int slow = 0, n = 0;
        while (System.nanoTime() < end) {
            LockSupport.parkNanos(start + n * period - System.nanoTime());
            long t = System.nanoTime();
            g.nextId();
            long lat = System.nanoTime() - t;
            max = Math.max(max, lat);
            if (lat > 50_000_000L) slow++;
            n++;
        }
        System.out.printf("%-14s | %,5d/s | %6.0f ms   | %,8d  | %4d 次 | %6.0f ms | %d%n",
                label, rate, 1000.0 * 1000 / rate, n, g.dbCalls.get() - calls0, max / 1e6, slow);
        g.close();
    }

    // ------------------------------------------------------------------ 雪花

    /** MyBatis-Plus 3.5.18 的 Sequence，只把 timeGen() 换成“系统时间 + 偏移”，用来模拟时钟回拨 */
    static final class ClockedSequence extends Sequence {
        volatile long offset;
        ClockedSequence(long workerId, long datacenterId) { super(workerId, datacenterId); }
        @Override protected long timeGen() { return System.currentTimeMillis() + offset; }
    }

    static final long MP_EPOCH = 1288834974657L;

    static String decode(long id) {
        return String.format("时间 %s | 机房 %d | 机器 %d | 序列 %d", Instant.ofEpochMilli((id >> 22) + MP_EPOCH),
                (id >> 17) & 31, (id >> 12) & 31, id & 4095);
    }

    static void snowflake() throws Exception {
        System.out.println("\n== snowflake ①：拆开一个 MyBatis-Plus 雪花 ID（Sequence(workerId=1, datacenterId=1)）");
        ClockedSequence s = new ClockedSequence(1, 1);
        long id = s.nextId();
        String bin = String.format("%64s", Long.toBinaryString(id)).replace(' ', '0');
        System.out.println("id = " + id + "（" + String.valueOf(id).length() + " 位十进制）");
        System.out.println("二进制 " + bin.charAt(0) + " | " + bin.substring(1, 42) + " | " + bin.substring(42, 47) + " | "
                + bin.substring(47, 52) + " | " + bin.substring(52));
        System.out.println("拆开：" + decode(id));
        System.out.println("转成 JS 的 Number（double）再转回来：" + (long) (double) id + "，差了 " + (id - (long) (double) id));
        Sequence auto = new Sequence(null);
        System.out.println("不给 workerId，让 MyBatis-Plus 自己算（本机）：" + decode(auto.nextId()).replaceAll("时间 \\S+ \\| ", ""));

        System.out.println("\n== snowflake ②：低流量（每毫秒最多 1 个）发 1000 个号，按 id % 4 和 id % 3 分库");
        int[] m4 = new int[4], m3 = new int[3];
        for (int i = 0; i < 1000; i++) { long v = s.nextId(); m4[(int) (v % 4)]++; m3[(int) (v % 3)]++; Thread.sleep(1); }
        System.out.println("id % 4 → 库 0..3 各落了 " + Arrays.toString(m4) + "；id % 3 → 库 0..2 各落了 " + Arrays.toString(m3));

        System.out.println("\n== snowflake ③：单线程一口气发 3,000,000 个");
        long t0 = System.nanoTime();
        long first = s.nextId(), last = first;
        for (int i = 1; i < 3_000_000; i++) last = s.nextId();
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("用时 %.2f s，%,.0f 个/秒；跨了 %d 毫秒（上限每毫秒 4096 个）%n", secs, 3_000_000 / secs,
                ((last >> 22) - (first >> 22)));

        System.out.println("\n== snowflake ④：时钟回拨（MyBatis-Plus 3.5.18 真实的 Sequence.nextId()）");
        for (long back : new long[]{3, 10, 1000}) {
            ClockedSequence c = new ClockedSequence(1, 1);
            for (int i = 0; i < 5; i++) c.nextId();
            c.offset = -back;
            long t = System.nanoTime();
            String r;
            try { c.nextId(); r = "成功，这一次调用花了 " + Math.round((System.nanoTime() - t) / 1e6) + " ms（sleep 了回拨量 × 2）"; }
            catch (RuntimeException e) { r = "抛异常：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()); }
            System.out.println("回拨 " + back + " ms → " + r);
            if (back == 1000) {
                int fail = 0; long okAt = -1; long t1 = System.nanoTime();
                while (okAt < 0) {
                    try { c.nextId(); okAt = (System.nanoTime() - t1) / 1_000_000; } catch (RuntimeException e) { fail++; }
                    Thread.sleep(1);
                }
                System.out.println("  之后每 1 ms 调一次：失败 " + fail + " 次，" + okAt + " ms 后才恢复（等墙上时钟走回原来的位置）");
            }
        }

        System.out.println("\n== snowflake ⑤：实例运行 300 ms 后重启，重启时钟被 NTP 往回拨了 300 ms（lastTimestamp 没存下来，查不出回拨）");
        ClockedSequence before = new ClockedSequence(1, 1);
        Set<Long> seen = new HashSet<>();
        long tEnd = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < tEnd) { for (int i = 0; i < 10; i++) seen.add(before.nextId()); Thread.sleep(1); }
        ClockedSequence after = new ClockedSequence(1, 1);
        after.offset = -300;
        int dupR = 0, total = 0;
        tEnd = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < tEnd) { for (int i = 0; i < 10; i++) { total++; if (seen.contains(after.nextId())) dupR++; } Thread.sleep(1); }
        System.out.printf("重启前发了 %,d 个；重启后 300 ms 发了 %,d 个，其中 %,d 个和重启前的重复%n", seen.size(), total, dupR);

        System.out.println("\n== snowflake ⑥：两个实例配成了同一个 workerId / datacenterId（1, 1）");
        dupTwins("低流量：各自每 1 ms 发 1 个，2 秒", true);
        dupTwins("高流量：各自一口气发 500,000 个", false);

        System.out.println("\n== snowflake ⑦：照 Seata 2.6.0 IdWorker 写的变种（启动后不再看钟）");
        final long[] off = {0};
        SeataStyleIdWorker w1 = new SeataStyleIdWorker(1, () -> System.currentTimeMillis() + off[0]);
        SeataStyleIdWorker w2 = new SeataStyleIdWorker(2, () -> System.currentTimeMillis() + off[0]);
        long fromW2 = w2.nextId();
        Thread.sleep(5);
        long fromW1 = w1.nextId();
        System.out.println("先在 worker 2 发：" + fromW2 + "，5 ms 后在 worker 1 发：" + fromW1 + " → 后发的反而小（workerId 在高位）");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) ids.add(w1.nextId());
        off[0] = -1000;
        long prev = w1.nextId(); boolean mono = true; int n = 1;
        long sleeps0 = w1.sleeps.get();
        long tStart = System.nanoTime();
        while (System.nanoTime() - tStart < 1_500_000_000L) { long v = w1.nextId(); if (v <= prev) mono = false; prev = v; if (!ids.add(v)) mono = false; n++; }
        System.out.printf("回拨 1000 ms 后连续发 1.5 s：发了 %,d 个，没有异常，没有重复，严格递增 = %s；其中 %,d 次调用 sleep 了 5 ms%n",
                n, mono, w1.sleeps.get() - sleeps0);
    }

    static void dupTwins(String label, boolean slow) throws Exception {
        final ClockedSequence a = new ClockedSequence(1, 1), b = new ClockedSequence(1, 1);
        final List<Long> la = new ArrayList<>(), lb = new ArrayList<>();
        Thread ta = new Thread(() -> gen(a, la, slow)), tb = new Thread(() -> gen(b, lb, slow));
        ta.start(); tb.start(); ta.join(); tb.join();
        Set<Long> sa = new HashSet<>(la);
        int dup = 0;
        for (long v : lb) if (sa.contains(v)) dup++;
        System.out.printf("%s：A 发 %,d 个，B 发 %,d 个，撞号 %,d 个%n", label, la.size(), lb.size(), dup);
    }

    static void gen(Sequence s, List<Long> out, boolean slow) {
        if (slow) {
            long end = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < end) { out.add(s.nextId()); LockSupport.parkNanos(1_000_000); }
        } else {
            for (int i = 0; i < 500_000; i++) out.add(s.nextId());
        }
    }

    // ------------------------------------------------------------------ B+ 树

    static long v7Ms = -1, v7Seq;

    /** UUID v7：高 48 位毫秒；同一毫秒内用 12 位计数器递增（RFC 9562 6.2 方法 1），保证单调 */
    static String uuidV7() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long ms = System.currentTimeMillis();
        if (ms == v7Ms && v7Seq < 0xFFF) v7Seq++;
        else { while (ms <= v7Ms) ms = System.currentTimeMillis(); v7Ms = ms; v7Seq = r.nextInt(256); }
        long msb = (ms << 16) | 0x7000L | v7Seq;
        long lsb = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    /** 同一毫秒内用随机数、不保证单调的 UUID v7（有些库就是这么生成的） */
    static String uuidV7Random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long msb = (System.currentTimeMillis() << 16) | 0x7000L | (r.nextLong() & 0x0FFFL);
        long lsb = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    static void btree() throws Exception {
        System.out.println("\n== btree：往 InnoDB 插 300,000 行（主键 + 100 字节 payload，每批 1000 行一个事务）");
        System.out.println("主键                          | 用时    | 页分裂（index_page_splits） | 表大小（DATA_LENGTH）");
        ClockedSequence seq = new ClockedSequence(1, 1);
        insert("BIGINT 雪花（递增）", "BIGINT", () -> seq.nextId());
        final ClockedSequence[] eight = new ClockedSequence[8];
        for (int i = 0; i < 8; i++) eight[i] = new ClockedSequence(i, 1);
        final int[] turn = {0};
        insert("BIGINT 雪花 8 个实例轮流写", "BIGINT", () -> eight[turn[0]++ & 7].nextId());
        insert("CHAR(36) 随机 UUID v4", "CHAR(36)", () -> UUID.randomUUID().toString());
        insert("CHAR(36) UUID v7 毫秒内随机", "CHAR(36)", IdLab::uuidV7Random);
        insert("CHAR(36) UUID v7 毫秒内递增", "CHAR(36)", IdLab::uuidV7);
    }

    interface Gen { Object next(); }

    static void insert(String label, String type, Gen gen) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "root", "root"); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS t_id");
            s.execute("CREATE TABLE t_id (id " + type + " PRIMARY KEY, payload VARCHAR(100) NOT NULL)");
            s.execute("SET GLOBAL innodb_monitor_enable = 'index_page_splits'");
            s.execute("SET GLOBAL innodb_monitor_reset = 'index_page_splits'");
            c.setAutoCommit(false);
            String payload = new String(new char[100]).replace('\0', 'x');
            long t0 = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_id VALUES (?, ?)")) {
                for (int i = 0; i < 300_000; i++) {
                    ps.setObject(1, gen.next());
                    ps.setString(2, payload);
                    ps.addBatch();
                    if (i % 1000 == 999) { ps.executeBatch(); c.commit(); }
                }
            }
            double secs = (System.nanoTime() - t0) / 1e9;
            c.setAutoCommit(true);
            long splits, size;
            try (ResultSet rs = s.executeQuery("SELECT COUNT_RESET FROM information_schema.INNODB_METRICS WHERE NAME = 'index_page_splits'")) {
                rs.next(); splits = rs.getLong(1);
            }
            s.execute("SET SESSION information_schema_stats_expiry = 0");
            s.execute("ANALYZE TABLE t_id");
            try (ResultSet rs = s.executeQuery("SELECT DATA_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'idlab' AND TABLE_NAME = 't_id'")) {
                rs.next(); size = rs.getLong(1);
            }
            System.out.printf("%-26s | %5.1f s | %,8d                    | %5.1f MB（%,d 页）%n", label, secs, splits, size / 1048576.0, size / 16384);
        }
    }
}
