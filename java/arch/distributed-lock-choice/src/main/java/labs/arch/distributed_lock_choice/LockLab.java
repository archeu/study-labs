package labs.arch.distributed_lock_choice;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingCluster;
import org.apache.curator.test.TestingServer;

/**
 * 交易靶场 J09 全景第 9 站：两个订单服务实例每秒扫 outbox，抢到锁的那个把 ORDER_CREATED 发到 Kafka。
 * “发到 Kafka”用往 kafka_log 表插一行代替（独立连接、自动提交，和 Kafka 一样不跟着业务事务回滚）。
 *
 *   scan      ⑤：五种写法各跑 5 轮，两个实例同时扫：不加锁 / FOR UPDATE 锁行 / 租约行 / ZooKeeper / SKIP LOCKED
 *   die       持锁的 A（单独一个 JVM）读完 outbox 后：被 kill -9 / 被 kill -STOP 卡住 8 秒 / 活着但发得慢（8 秒）
 *             B 等多久拿到锁，9527 一共发了几次
 *   failover  3 节点 ZooKeeper：A 持锁时杀掉 leader，锁还在吗；再杀一台（凑不够多数派）A 收到什么
 *   all       依次跑三段
 */
public class LockLab {
    static final String URL = System.getProperty("db.url",
            "jdbc:mysql://127.0.0.1:33096/lab?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true");
    static final int SEND_MS = 50;        // 模拟一次 Kafka send 的耗时
    static final int LEASE_S = 5;         // 租约行的租期
    static final int SESSION_MS = 5000;   // ZooKeeper 会话超时
    static final String LOCK = "outbox-scan";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        if (mode.equals("holder")) { holder(args[1], args[2], Long.parseLong(args[3])); return; }
        if (mode.equals("scan") || mode.equals("all")) scanMode();
        if (mode.equals("die") || mode.equals("all")) dieMode();
        if (mode.equals("failover") || mode.equals("all")) failoverMode();
        System.exit(0);
    }

    // ---------------------------------------------------------------- 表
    static Connection db() throws SQLException { return DriverManager.getConnection(URL, "root", "root"); }

    static void reset(int orders) throws SQLException {
        try (Connection c = db(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS outbox, kafka_log, scan_lock, lease");
            st.execute("CREATE TABLE outbox(id BIGINT PRIMARY KEY, status VARCHAR(8) NOT NULL, sent_by VARCHAR(4))");
            st.execute("CREATE TABLE kafka_log(seq BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, sent_by VARCHAR(4), token BIGINT)");
            st.execute("CREATE TABLE scan_lock(name VARCHAR(32) PRIMARY KEY)");
            st.execute("CREATE TABLE lease(name VARCHAR(32) PRIMARY KEY, owner VARCHAR(4), token BIGINT, expire_at DATETIME(3))");
            st.execute("INSERT INTO scan_lock VALUES ('" + LOCK + "')");
            st.execute("INSERT INTO lease VALUES ('" + LOCK + "', '', 0, '2000-01-01')");
            for (int i = 0; i < orders; i++) st.execute("INSERT INTO outbox VALUES (" + (9527 + i) + ", 'NEW', NULL)");
        }
    }

    static long one(String sql) throws SQLException {
        try (Connection c = db(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next(); return rs.getLong(1);
        }
    }

    // ---------------------------------------------------------------- 三种锁
    interface Lock {
        /** 拿到返回 fencing token（没有就返回 0），拿不到返回 -1 */
        long tryLock(long waitMs) throws Exception;
        void unlock() throws Exception;
        /** 持锁的一方自己以为还持着锁吗 */
        boolean believes();
    }

    /** 数据库行锁：事务里 SELECT … FOR UPDATE，提交才放锁；业务写也在这个事务里 */
    static class ForUpdateLock implements Lock {
        final Connection c; boolean held;
        ForUpdateLock(Connection c) throws SQLException {
            this.c = c; c.setAutoCommit(false);
            try (Statement st = c.createStatement()) { st.execute("SET SESSION innodb_lock_wait_timeout = 1"); }
        }
        public long tryLock(long waitMs) throws Exception {
            long end = System.currentTimeMillis() + waitMs;
            while (true) {
                try (Statement st = c.createStatement()) {
                    st.executeQuery("SELECT name FROM scan_lock WHERE name = '" + LOCK + "' FOR UPDATE").close();
                    held = true; return 0;
                } catch (SQLException e) {
                    if (e.getErrorCode() != 1205) throw e;           // 1205 = Lock wait timeout exceeded
                    if (System.currentTimeMillis() >= end) { c.rollback(); return -1; }
                }
            }
        }
        public void unlock() throws Exception { c.commit(); held = false; }
        public boolean believes() { return held; }
    }

    /** 数据库租约行：一条 UPDATE 抢，到期时间用数据库的时钟，token 每次加 1 */
    static class LeaseLock implements Lock {
        final Connection c; final String me; long token = -1;
        LeaseLock(Connection c, String me) { this.c = c; this.me = me; }
        public long tryLock(long waitMs) throws Exception {
            long end = System.currentTimeMillis() + waitMs;
            while (true) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE lease SET owner = ?, token = token + 1, expire_at = NOW(3) + INTERVAL " + LEASE_S + " SECOND " +
                        "WHERE name = ? AND (expire_at < NOW(3) OR owner = ?)")) {
                    ps.setString(1, me); ps.setString(2, LOCK); ps.setString(3, me);
                    if (ps.executeUpdate() == 1) {
                        try (Statement st = c.createStatement();
                             ResultSet rs = st.executeQuery("SELECT token FROM lease WHERE name = '" + LOCK + "'")) {
                            rs.next(); token = rs.getLong(1); return token;
                        }
                    }
                }
                if (System.currentTimeMillis() >= end) return -1;
                Thread.sleep(100);
            }
        }
        public void unlock() throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE lease SET expire_at = NOW(3) WHERE name = ? AND owner = ? AND token = ?")) {
                ps.setString(1, LOCK); ps.setString(2, me); ps.setLong(3, token);
                ps.executeUpdate();          // 别人已经抢走时影响 0 行，不会误删
            }
            token = -1;
        }
        public boolean believes() { return token >= 0; }
    }

    /** ZooKeeper：Curator 的 InterProcessMutex，底下是 /locks/outbox-scan 下的临时顺序节点 */
    static class ZkLock implements Lock {
        static class Mutex extends InterProcessMutex {
            Mutex(CuratorFramework c, String p) { super(c, p); }
            String path() { return getLockPath(); }
        }
        final Mutex m; String node;
        ZkLock(CuratorFramework zk) { m = new Mutex(zk, "/locks/" + LOCK); }
        public long tryLock(long waitMs) throws Exception {
            if (!m.acquire(waitMs, TimeUnit.MILLISECONDS)) return -1;
            node = m.path();                                     // …/_c_<uuid>-lock-0000000003
            return Long.parseLong(node.substring(node.length() - 10));
        }
        public void unlock() throws Exception { m.release(); }
        public boolean believes() { return m.isAcquiredInThisProcess(); }
    }

    /** 不加锁 */
    static class NoLock implements Lock {
        public long tryLock(long waitMs) { return 0; }
        public void unlock() {}
        public boolean believes() { return true; }
    }

    // ---------------------------------------------------------------- 一个订单服务实例
    static class Inst {
        final String name, kind; final Connection c, kafka; final Lock lock;
        long gotLockAt; int marked;
        Inst(String name, String kind, CuratorFramework zk) throws SQLException {
            this.name = name; this.kind = kind; this.c = db(); this.kafka = db();
            switch (kind) {
                case "forupdate": lock = new ForUpdateLock(c); break;
                case "lease": lock = new LeaseLock(c, name); break;
                case "zk": lock = new ZkLock(zk); break;
                case "skiplocked": c.setAutoCommit(false); lock = null; break;
                default: lock = new NoLock();
            }
        }

        /** 扫一次 outbox；没抢到锁返回 -1，否则返回发了几条 */
        int scan(long waitMs, Runnable afterRead) throws Exception {
            if (kind.equals("skiplocked")) return scanSkipLocked();
            long token = lock.tryLock(waitMs);
            if (token < 0) return -1;
            gotLockAt = System.currentTimeMillis();
            try {
                List<Long> ids = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id FROM outbox WHERE status = 'NEW' ORDER BY id")) {
                    while (rs.next()) ids.add(rs.getLong(1));
                }
                if (afterRead != null) afterRead.run();
                for (long id : ids) send(id, token);
                for (long id : ids) marked += mark(id);
                return ids.size();
            } finally {
                try { lock.unlock(); } catch (Exception e) { out(name + " 放锁失败：" + e.getClass().getSimpleName()); }
            }
        }

        /** 不要锁：每个实例一次只锁一行 outbox，别人锁着的行直接跳过 */
        int scanSkipLocked() throws Exception {
            int n = 0;
            while (true) {
                Long id = null;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                        "SELECT id FROM outbox WHERE status = 'NEW' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
                    if (rs.next()) id = rs.getLong(1);
                }
                if (id == null) { c.commit(); return n; }
                send(id, 0); marked += mark(id); c.commit(); n++;
            }
        }

        void send(long id, long token) throws Exception {
            try (PreparedStatement ps = kafka.prepareStatement("INSERT INTO kafka_log(order_id, sent_by, token) VALUES (?, ?, ?)")) {
                ps.setLong(1, id); ps.setString(2, name); ps.setLong(3, token); ps.executeUpdate();
            }
            Thread.sleep(SEND_MS);
        }

        int mark(long id) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("UPDATE outbox SET status = 'SENT', sent_by = ? WHERE id = ? AND status = 'NEW'")) {
                ps.setString(1, name); ps.setLong(2, id); return ps.executeUpdate();
            }
        }
    }

    // ---------------------------------------------------------------- ZooKeeper
    static CuratorFramework zk(String conn) throws InterruptedException {
        CuratorFramework c = CuratorFrameworkFactory.builder().connectString(conn)
                .sessionTimeoutMs(SESSION_MS).connectionTimeoutMs(3000).retryPolicy(new RetryNTimes(1000, 200)).build();
        c.start();
        c.blockUntilConnected(10, TimeUnit.SECONDS);
        return c;
    }

    static TestingServer zkServer() throws Exception {
        // tickTime 500 ms：会话超时可以协商到 1～10 秒，5 秒在范围里
        return new TestingServer(new InstanceSpec(null, -1, -1, -1, true, -1, 500, -1), true);
    }

    // ---------------------------------------------------------------- scan
    static void scanMode() throws Exception {
        String[][] kinds = {{"none", "不加锁"}, {"forupdate", "FOR UPDATE 锁一行"}, {"lease", "租约行（5 秒）"},
                {"zk", "ZooKeeper 临时顺序节点"}, {"skiplocked", "SKIP LOCKED（不用锁）"}};
        out("== scan：两个实例同时扫 outbox，5 轮，每轮新来 2 单，send 一次 " + SEND_MS + " ms");
        out("写法                     | 发出 | 重复 | A 发 | B 发 | 没抢到锁、直接回去（次）");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (TestingServer zs = zkServer()) {
            for (String[] k : kinds) {
                reset(0);
                CuratorFramework za = k[0].equals("zk") ? zk(zs.getConnectString()) : null;
                CuratorFramework zb = k[0].equals("zk") ? zk(zs.getConnectString()) : null;
                Inst a = new Inst("A", k[0], za), b = new Inst("B", k[0], zb);
                int skipped = 0;
                for (int r = 0; r < 5; r++) {
                    try (Connection c = db(); Statement st = c.createStatement()) {
                        st.execute("INSERT INTO outbox VALUES (" + (9527 + 2 * r) + ", 'NEW', NULL), (" + (9528 + 2 * r) + ", 'NEW', NULL)");
                    }
                    CountDownLatch go = new CountDownLatch(1);
                    Future<Integer> fa = pool.submit(() -> { go.await(); return a.scan(0, null); });
                    Future<Integer> fb = pool.submit(() -> { go.await(); return b.scan(0, null); });
                    go.countDown();
                    if (fa.get() < 0) skipped++;
                    if (fb.get() < 0) skipped++;
                }
                long sent = one("SELECT COUNT(*) FROM kafka_log");
                long distinct = one("SELECT COUNT(DISTINCT order_id) FROM kafka_log");
                long byA = one("SELECT COUNT(*) FROM kafka_log WHERE sent_by = 'A'");
                out(String.format("%-22s | %4d | %4d | %4d | %4d | %d", k[1], sent, sent - distinct, byA, sent - byA, skipped));
                if (za != null) { za.close(); zb.close(); }
            }
        }
        pool.shutdown();
    }

    // ---------------------------------------------------------------- die
    static void dieMode() throws Exception {
        out("");
        out("== die：持锁的 A（单独的 JVM）读完 outbox 里的 9527 之后出事，B 每 100 ms～1 s 抢一次锁");
        out("锁           | A 怎么了                  | B 等了多久拿到锁 | 9527 发了几次（谁, token） | A 醒来时还以为持锁吗");
        String[][] kinds = {{"forupdate", "FOR UPDATE"}, {"lease", "租约行 5s"}, {"zk", "ZK 会话 5s"}};
        String[][] hows = {{"kill", "kill -9"}, {"stop", "kill -STOP 卡 8 秒"}, {"slow", "活着，但发得慢（8 秒）"}};
        try (TestingServer zs = zkServer()) {
            for (String[] k : kinds) for (String[] h : hows) dieOnce(zs, k, h);
        }
    }

    static void dieOnce(TestingServer zs, String[] k, String[] h) throws Exception {
        reset(1);
        long pauseMs = h[0].equals("slow") ? 8000 : 1000;
        String java = System.getProperty("java.home") + "/bin/java";
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LockLab.class.getName(),
                "holder", k[0], zs.getConnectString(), String.valueOf(pauseMs)).redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String pid = null, line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("PID ")) pid = line.substring(4);
            if (line.startsWith("READY")) break;
        }
        if (line == null) throw new IllegalStateException("A 没有拿到锁就退出了");
        long t0 = System.currentTimeMillis();
        if (h[0].equals("kill")) sh("kill", "-KILL", pid);
        if (h[0].equals("stop")) {
            sh("kill", "-STOP", pid);
            final String fpid = pid;
            new Thread(() -> { sleep(8000); sh("kill", "-CONT", fpid); }).start();
        }
        CuratorFramework zb = k[0].equals("zk") ? zk(zs.getConnectString()) : null;
        Inst b = new Inst("B", k[0], zb);
        int n = b.scan(15000, null);
        String waited = n < 0 ? "15 s 内没拿到" : String.format("%.1f s", (b.gotLockAt - t0) / 1000.0);
        // 等 A 结束，收它最后几行
        String believes = "-";
        while ((line = r.readLine()) != null) if (line.startsWith("WOKE")) believes = line.endsWith("true") ? "以为还持着" : "知道丢了";
        p.waitFor(20, TimeUnit.SECONDS);
        if (h[0].equals("kill")) believes = "（已死）";
        StringBuilder sends = new StringBuilder();
        try (Connection c = db(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT sent_by, token FROM kafka_log WHERE order_id = 9527 ORDER BY seq")) {
            int cnt = 0;
            while (rs.next()) { sends.append(cnt++ == 0 ? "" : ", ").append(rs.getString(1)).append(k[0].equals("forupdate") ? "" : "#" + rs.getLong(2)); }
            sends.insert(0, cnt + " 次（").append("）");
        }
        out(String.format("%-12s | %-22s | %-14s | %-24s | %s", k[1], h[1], waited, sends, believes));
        if (zb != null) zb.close();
        b.c.close(); b.kafka.close();
    }

    /** 子进程里的 A：拿锁、读 outbox，打印 READY，然后睡 pauseMs（被 STOP 时醒来就晚了），再发、再标 SENT */
    static void holder(String kind, String zkConn, long pauseMs) throws Exception {
        System.out.println("PID " + ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        CuratorFramework zk = kind.equals("zk") ? zk(zkConn) : null;
        Inst a = new Inst("A", kind, zk);
        a.scan(5000, () -> {
            System.out.println("READY"); System.out.flush();
            sleep(pauseMs);
            System.out.println("WOKE believes=" + a.lock.believes()); System.out.flush();
        });
        System.out.println("DONE marked=" + a.marked);
        System.exit(0);
    }

    // ---------------------------------------------------------------- failover
    static void failoverMode() throws Exception {
        out("");
        out("== failover：3 节点 ZooKeeper，A 持锁时先杀 leader，再杀一台");
        System.setProperty("zookeeper.4lw.commands.whitelist", "srvr");
        List<InstanceSpec> specs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) specs.add(new InstanceSpec(null, -1, -1, -1, true, i, 500, -1));
        long t0 = System.currentTimeMillis();
        try (TestingCluster cl = new TestingCluster(specs)) {
            cl.start();
            CuratorFramework a = zk(cl.getConnectString()), b = zk(cl.getConnectString());
            out("会话超时协商结果：" + a.getZookeeperClient().getZooKeeper().getSessionTimeout() + " ms");
            a.getConnectionStateListenable().addListener((c, s) ->
                    out(String.format("  [%5.1f s] A 的连接状态 → %s", (System.currentTimeMillis() - t0) / 1000.0, s)));
            ZkLock la = new ZkLock(a), lb = new ZkLock(b);
            long token = la.tryLock(5000);
            InstanceSpec leader = leader(specs);
            out("A 持锁，节点 " + la.node + "（token " + token + "），A 连在 server " + cl.findConnectionInstance(a.getZookeeperClient().getZooKeeper()).getServerId()
                    + "，leader 是 server " + leader.getServerId());
            cl.killServer(leader);
            out(String.format("[%5.1f s] 杀掉 leader server %d", (System.currentTimeMillis() - t0) / 1000.0, leader.getServerId()));
            Thread.sleep(3000);
            InstanceSpec nl = leader(specs);
            out("3 秒后：新 leader 是 server " + (nl == null ? "（还没选出）" : String.valueOf(nl.getServerId()))
                    + "，锁节点还在吗：" + (b.checkExists().forPath(la.node) != null)
                    + "，B 再抢 2 秒：" + (lb.tryLock(2000) >= 0 ? "抢到了" : "抢不到"));
            InstanceSpec second = nl != null ? nl : specs.get(leader == specs.get(0) ? 1 : 0);
            cl.killServer(second);
            out(String.format("[%5.1f s] 再杀 server %d，只剩 1 台，凑不够多数派", (System.currentTimeMillis() - t0) / 1000.0, second.getServerId()));
            // 连不上集群时 acquire 会一直卡在 Curator 的重试里，所以放到后台线程，只等 4 秒
            ExecutorService bg = Executors.newSingleThreadExecutor(rn -> { Thread t = new Thread(rn); t.setDaemon(true); return t; });
            Future<Long> fb = bg.submit(() -> lb.tryLock(4000));
            String got;
            try { got = fb.get(4, TimeUnit.SECONDS) >= 0 ? "抢到了" : "抢不到"; }
            catch (Exception e) { got = "4 秒没有返回（连不上，卡在重试里）"; }
            out("B 抢锁：" + got);
            Thread.sleep(6000);
            out(String.format("[%5.1f s] A 自己以为还持锁吗（isAcquiredInThisProcess）：%s", (System.currentTimeMillis() - t0) / 1000.0, la.believes()));
        }
    }

    static InstanceSpec leader(List<InstanceSpec> specs) {
        for (InstanceSpec s : specs) {
            try (Socket so = new Socket("127.0.0.1", s.getPort())) {
                so.setSoTimeout(1000);
                OutputStream o = so.getOutputStream(); o.write("srvr".getBytes(StandardCharsets.US_ASCII)); o.flush();
                InputStream in = so.getInputStream(); byte[] buf = new byte[4096]; int n, len = 0;
                while ((n = in.read(buf, len, buf.length - len)) > 0) len += n;
                if (new String(buf, 0, len, StandardCharsets.US_ASCII).contains("Mode: leader")) return s;
            } catch (Exception e) { /* 已经杀掉或还没起来 */ }
        }
        return null;
    }

    // ---------------------------------------------------------------- 小工具
    static void out(String s) { System.out.println(s); System.out.flush(); }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static void sh(String... cmd) {
        try { new ProcessBuilder(cmd).inheritIO().start().waitFor(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
