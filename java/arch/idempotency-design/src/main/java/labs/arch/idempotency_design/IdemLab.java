package labs.arch.idempotency_design;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 交易靶场 · J09 幂等设计。连一个 MySQL 8.4，分四段：
 *   entry  第 1 站：同一个幂等键的两次 POST，第二下晚到 0..19 ms，三种写法各出了几张单、第二下拿到什么
 *   crash  第 1 站：第一下处理到一半实例死了，同一个键再来一次会怎样
 *   state  第 6、8 站：券和库存的 Confirm 被 TC 重试 3 次，状态机写法和“再减 2”写法的差别
 *   dedup  第 8 站：去重表和业务在不在同一个本地事务，崩在两次提交之间时会不会多扣
 * 参数：段名 [JDBC URL]，默认 jdbc:mysql://127.0.0.1:33094/shop
 */
public class IdemLab {
    static String url = "jdbc:mysql://127.0.0.1:33094/shop";
    static final int PAIRS = 400;      // 400 个幂等键，每个键来两下
    static final int WORK_MS = 10;     // 第一下从“判断”到“落单”中间的业务耗时（算价、调库存……）

    public static void main(String[] a) throws Exception {
        if (a.length > 1) url = a[1];
        String step = a.length > 0 ? a[0] : "all";
        if (step.equals("setup") || step.equals("all")) setup();
        if (step.equals("entry") || step.equals("all")) entry();
        if (step.equals("crash") || step.equals("all")) crash();
        if (step.equals("state") || step.equals("all")) state();
        if (step.equals("dedup") || step.equals("all")) dedup();
    }

    static Connection conn() throws SQLException {
        Connection c = DriverManager.getConnection(url + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "root");
        try (Statement s = c.createStatement()) { s.execute("SET SESSION innodb_lock_wait_timeout = 5"); }
        return c;
    }

    static void exec(Connection c, String... sqls) throws SQLException {
        try (Statement s = c.createStatement()) { for (String q : sqls) s.execute(q); }
    }

    static long one(Connection c, String q) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(q)) { return r.next() ? r.getLong(1) : -1; }
    }

    static void setup() throws Exception {
        try (Connection c = DriverManager.getConnection(url.replace("/shop", "/") + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "root")) {
            exec(c, "DROP DATABASE IF EXISTS shop", "CREATE DATABASE shop");
        }
        try (Connection c = conn()) {
            exec(c,
                // 订单表：req_key 只是一列普通索引，不唯一（写法 1 用它“先查后插”）
                "CREATE TABLE orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, req_key VARCHAR(64), user_id BIGINT, sku_id BIGINT, qty INT," +
                " status VARCHAR(16), KEY idx_req (req_key))",
                // 幂等键表：主键就是唯一键
                "CREATE TABLE idem_key (k VARCHAR(64) PRIMARY KEY, req_hash CHAR(8), status VARCHAR(16), order_id BIGINT," +
                " created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3))",
                "CREATE TABLE user_coupon (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(16), order_id BIGINT)",
                "CREATE TABLE stock (sku_id BIGINT PRIMARY KEY, available INT, frozen INT, sold INT)",
                // 每张单冻结了多少：一行一个状态机 FROZEN → CONFIRMED / CANCELLED
                "CREATE TABLE stock_freeze (order_id BIGINT, sku_id BIGINT, qty INT, status VARCHAR(16), PRIMARY KEY (order_id, sku_id))",
                // 去重表：(xid, branch_id) 处理过就有一行
                "CREATE TABLE confirm_done (xid VARCHAR(64), branch_id BIGINT, PRIMARY KEY (xid, branch_id))");
        }
        System.out.println("[setup] shop 库建好：orders、idem_key、user_coupon、stock、stock_freeze、confirm_done");
    }

    // ---------------- entry ----------------

    interface Writer { String handle(Connection c, String key) throws Exception; }

    /** 写法 1：先查有没有，没有再插（没有唯一约束） */
    static String checkThenInsert(Connection c, String key) throws Exception {
        if (one(c, "SELECT COUNT(*) FROM orders WHERE req_key='" + key + "'") > 0) return "重复，已挡";
        Thread.sleep(WORK_MS);
        exec(c, "INSERT INTO orders(req_key,user_id,sku_id,qty,status) VALUES('" + key + "',1024,88,2,'WAIT_PAY')");
        return "新单";
    }

    /** 写法 2：插幂等键 + 落单 + 写结果，三件事在同一个本地事务里 */
    static String keyInSameTx(Connection c, String key) throws Exception {
        c.setAutoCommit(false);
        try {
            exec(c, "INSERT INTO idem_key(k,req_hash,status) VALUES('" + key + "','h','PROCESSING')");
        } catch (SQLException e) {
            c.rollback(); c.setAutoCommit(true);
            if (e.getErrorCode() != 1062) throw e;
            return replay(c, key);
        }
        Thread.sleep(WORK_MS);
        exec(c, "INSERT INTO orders(req_key,user_id,sku_id,qty,status) VALUES('" + key + "',1024,88,2,'WAIT_PAY')");
        long id = one(c, "SELECT LAST_INSERT_ID()");
        exec(c, "UPDATE idem_key SET status='DONE', order_id=" + id + " WHERE k='" + key + "'");
        c.commit(); c.setAutoCommit(true);
        return "新单";
    }

    /** 写法 3：先单独提交幂等键（占位），再做业务、回写结果 */
    static String keyCommittedFirst(Connection c, String key) throws Exception {
        try {
            exec(c, "INSERT INTO idem_key(k,req_hash,status) VALUES('" + key + "','h','PROCESSING')");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1062) throw e;
            return replay(c, key);
        }
        Thread.sleep(WORK_MS);
        exec(c, "INSERT INTO orders(req_key,user_id,sku_id,qty,status) VALUES('" + key + "',1024,88,2,'WAIT_PAY')");
        long id = one(c, "SELECT LAST_INSERT_ID()");
        exec(c, "UPDATE idem_key SET status='DONE', order_id=" + id + " WHERE k='" + key + "'");
        return "新单";
    }

    /** 撞上唯一键之后：看第一下的状态，DONE 就把同一个结果再返回一次 */
    static String replay(Connection c, String key) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT status, order_id FROM idem_key WHERE k='" + key + "'")) {
            if (!r.next()) return "键不见了";
            return "DONE".equals(r.getString(1)) ? "重放同一单" : "409 处理中";
        }
    }

    static void entry() throws Exception {
        System.out.printf("[entry] %d 个幂等键，每个键来两下；第二下比第一下晚 0..19 ms（均匀），第一下从判断到落单要 %d ms%n", PAIRS, WORK_MS);
        String[] names = {"写法 1 先查后插（无唯一约束）", "写法 2 唯一键，键和订单同一个事务", "写法 3 唯一键，先单独提交键再落单"};
        Writer[] ws = {IdemLab::checkThenInsert, IdemLab::keyInSameTx, IdemLab::keyCommittedFirst};
        for (int w = 0; w < ws.length; w++) {
            try (Connection c = conn()) { exec(c, "TRUNCATE orders", "TRUNCATE idem_key"); }
            Map<String, AtomicInteger> second = new TreeMap<>();
            long waitedMax = 0, waitedSum = 0; int waitedN = 0;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try (Connection c1 = conn(); Connection c2 = conn()) {
                for (int i = 0; i < PAIRS; i++) {
                    String key = "req-" + w + "-" + i;
                    int gap = i % 20;
                    Writer wr = ws[w];
                    Future<String> f1 = pool.submit(() -> wr.handle(c1, key));
                    Thread.sleep(gap);
                    long t0 = System.nanoTime();
                    Future<String> f2 = pool.submit(() -> wr.handle(c2, key));
                    String r2 = f2.get();
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    f1.get();
                    second.computeIfAbsent(r2, k -> new AtomicInteger()).incrementAndGet();
                    if (w == 1 && "重放同一单".equals(r2) && gap < WORK_MS - 2) { waitedSum += ms; waitedN++; waitedMax = Math.max(waitedMax, ms); }
                }
                long dupKeys = one(c1, "SELECT COUNT(*) FROM (SELECT req_key FROM orders GROUP BY req_key HAVING COUNT(*)>1) t");
                long orders = one(c1, "SELECT COUNT(*) FROM orders");
                System.out.printf("  %s：%d 张单，%d 个键出了两张 | 第二下拿到：%s%n", names[w], orders, dupKeys, second);
                if (w == 1 && waitedN > 0)
                    System.out.printf("      其中晚到不足 %d ms 的 %d 次，第二下的 INSERT 在唯一键上等第一下提交，平均 %d ms、最多 %d ms，然后才撞上 1062%n",
                            WORK_MS - 2, waitedN, waitedSum / waitedN, waitedMax);
            } finally { pool.shutdownNow(); }
        }
    }

    // ---------------- crash ----------------

    static void crash() throws Exception {
        System.out.println("[crash] 第一下插完幂等键、还没落单时实例被 kill -9（这里用 Connection.abort 掐断连接），用户再点一次，键还是 req-7f3a");
        for (int w = 0; w < 2; w++) {
            try (Connection c = conn()) { exec(c, "TRUNCATE orders", "TRUNCATE idem_key"); }
            Connection dying = conn();
            if (w == 0) dying.setAutoCommit(false);      // 写法 2：键在事务里，还没提交
            exec(dying, "INSERT INTO idem_key(k,req_hash,status) VALUES('req-7f3a','h','PROCESSING')");
            dying.abort(Runnable::run);                    // 进程没了，连接断开，服务端回滚未提交的事务
            Thread.sleep(200);
            try (Connection c = conn()) {
                String r = w == 0 ? keyInSameTx(c, "req-7f3a") : keyCommittedFirst(c, "req-7f3a");
                String r2 = w == 0 ? keyInSameTx(c, "req-7f3a") : keyCommittedFirst(c, "req-7f3a");
                System.out.printf("  %s：重试拿到「%s」，再试一次「%s」；orders 里 %d 行，idem_key 状态 %s%n",
                        w == 0 ? "写法 2 键和订单同一个事务" : "写法 3 先单独提交键",
                        r, r2, one(c, "SELECT COUNT(*) FROM orders"), str(c, "SELECT IFNULL(MAX(status),'(无)') FROM idem_key"));
            }
        }
    }

    static String str(Connection c, String q) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(q)) { return r.next() ? r.getString(1) : null; }
    }

    // ---------------- state ----------------

    static void state() throws Exception {
        try (Connection c = conn()) {
            System.out.println("[state] 券 MINUS20（id 7）：Try 锁给 9527，然后 TC 把 Confirm 发了 3 次（第一次的应答丢了）");
            exec(c, "DELETE FROM user_coupon", "INSERT INTO user_coupon VALUES (7,1024,'UNUSED',NULL)");
            int t = upd(c, "UPDATE user_coupon SET status='LOCKED', order_id=9527 WHERE id=7 AND status='UNUSED'");
            int t2 = upd(c, "UPDATE user_coupon SET status='LOCKED', order_id=9528 WHERE id=7 AND status='UNUSED'");
            System.out.printf("  Try 9527 改了 %d 行；同时另一单 9528 也来锁，改了 %d 行%n", t, t2);
            for (int i = 1; i <= 3; i++) {
                int n = upd(c, "UPDATE user_coupon SET status='USED' WHERE id=7 AND status='LOCKED' AND order_id=9527");
                String verdict = n == 1 ? "成功" : couponVerdict(c, 9527);
                System.out.printf("  第 %d 次 Confirm：改了 %d 行 → %s%n", i, n, verdict);
            }
            int cancel = upd(c, "UPDATE user_coupon SET status='UNUSED', order_id=NULL WHERE id=7 AND status='LOCKED' AND order_id=9527");
            System.out.printf("  迟到的 Cancel（乱序）：改了 %d 行 → %s；券现在 %s%n", cancel,
                    cancel == 1 ? "退回了" : "状态已不是 LOCKED，Cancel 不动它（记日志、报警）",
                    str(c, "SELECT CONCAT(status,' / ',order_id) FROM user_coupon WHERE id=7"));

            System.out.println("[state] 库存 sku 88：9527 和 9528 各冻结 2 件，然后 9527 的 Confirm 被发了 3 次");
            for (int w = 0; w < 2; w++) {
                exec(c, "DELETE FROM stock", "DELETE FROM stock_freeze", "INSERT INTO stock VALUES (88,46,4,0)",
                        "INSERT INTO stock_freeze VALUES (9527,88,2,'FROZEN'),(9528,88,2,'FROZEN')");
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= 3; i++) sb.append(confirmStock(c, w, 9527) ? "成功 " : "失败 ");
                boolean ok9528 = confirmStock(c, w, 9528);
                System.out.printf("  %s：9527 三次 %s| 9528 的 Confirm %s | stock 可售 %d 冻结 %d 已售 %d%n",
                        w == 0 ? "写法 A `frozen = frozen - 2 WHERE frozen >= 2`" : "写法 B 冻结记录 FROZEN→CONFIRMED，改到 1 行才动 stock",
                        sb, ok9528 ? "成功" : "失败（冻结量不够了）",
                        one(c, "SELECT available FROM stock"), one(c, "SELECT frozen FROM stock"), one(c, "SELECT sold FROM stock"));
            }
        }
    }

    static int upd(Connection c, String q) throws SQLException { try (Statement s = c.createStatement()) { return s.executeUpdate(q); } }

    /** 改了 0 行：看现在的状态，判断是“已经做过了”还是“状态不对” */
    static String couponVerdict(Connection c, long order) throws SQLException {
        String s = str(c, "SELECT CONCAT(status,':',IFNULL(order_id,0)) FROM user_coupon WHERE id=7");
        if (("USED:" + order).equals(s)) return "已经是 USED 且属于 " + order + "，当成功返回（幂等）";
        return "现在是 " + s + "，状态不对，拒绝";
    }

    static boolean confirmStock(Connection c, int w, long order) throws SQLException {
        if (w == 0) return upd(c, "UPDATE stock SET frozen=frozen-2, sold=sold+2 WHERE sku_id=88 AND frozen>=2") == 1;
        c.setAutoCommit(false);
        try {
            int n = upd(c, "UPDATE stock_freeze SET status='CONFIRMED' WHERE order_id=" + order + " AND sku_id=88 AND status='FROZEN'");
            if (n == 1) upd(c, "UPDATE stock SET frozen=frozen-2, sold=sold+2 WHERE sku_id=88");
            c.commit();
            return n == 1 || "CONFIRMED".equals(str(c, "SELECT status FROM stock_freeze WHERE order_id=" + order));
        } finally { c.setAutoCommit(true); }
    }

    // ---------------- dedup ----------------

    static void dedup() throws Exception {
        final int N = 300;
        System.out.printf("[dedup] %d 个分支的 Confirm；每 3 个里有 1 个在“业务改完”和“记去重表”之间崩溃，TC 一直重试到成功%n", N);
        for (int w = 0; w < 2; w++) {
            try (Connection c = conn()) { exec(c, "TRUNCATE confirm_done", "DELETE FROM stock", "INSERT INTO stock VALUES (88,0,100000,0)"); }
            int calls = 0;
            for (int b = 0; b < N; b++) {
                boolean crashOnce = b % 3 == 0;
                boolean done = false;
                while (!done) {
                    calls++;
                    Connection c = conn();
                    try {
                        done = confirmWithDedup(c, w == 1, "xid-9527", b, crashOnce);
                    } catch (Crash e) {
                        crashOnce = false;   // 重启后不会再崩在同一处
                    } finally { if (!c.isClosed()) c.close(); }
                }
            }
            try (Connection c = conn()) {
                long sold = one(c, "SELECT sold FROM stock");
                System.out.printf("  %s：TC 调了 %d 次，去重表 %d 行，已售 %d 件（应为 %d）→ 多扣 %d 件%n",
                        w == 0 ? "去重记录单独提交（业务先提交）" : "去重记录和业务同一个本地事务",
                        calls, one(c, "SELECT COUNT(*) FROM confirm_done"), sold, N * 2, sold - N * 2);
            }
        }
        // 两个 Confirm 同时到（TC 以为第一个超时了，又发了一个）
        try (Connection c = conn()) { exec(c, "TRUNCATE confirm_done", "DELETE FROM stock", "INSERT INTO stock VALUES (88,0,100000,0)"); }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int both = 0;
        for (int b = 0; b < 100; b++) {
            final int br = b;
            CyclicBarrier go = new CyclicBarrier(2);
            Callable<Boolean> call = () -> { try (Connection c = conn()) { go.await(); return confirmWithDedup(c, true, "xid-9528", br, false); } };
            Future<Boolean> f1 = pool.submit(call), f2 = pool.submit(call);
            if (f1.get() && f2.get()) both++;
        }
        pool.shutdown();
        try (Connection c = conn()) {
            System.out.printf("  两个 Confirm 同时到 × 100 个分支：两次都返回成功的 %d 个，已售 %d 件（应为 200）%n", both, one(c, "SELECT sold FROM stock"));
        }
    }

    static class Crash extends RuntimeException {}

    static boolean confirmWithDedup(Connection c, boolean sameTx, String xid, int branch, boolean crash) throws SQLException {
        String ins = "INSERT INTO confirm_done VALUES ('" + xid + "'," + branch + ")";
        if (sameTx) {
            c.setAutoCommit(false);
            try {
                upd(c, ins);                                   // 先占位：重复的那个在这里撞 1062（或等对方提交后撞）
            } catch (SQLException e) {
                c.rollback();
                if (e.getErrorCode() == 1062) return true;     // 做过了，直接回成功
                throw e;
            }
            upd(c, "UPDATE stock SET frozen=frozen-2, sold=sold+2 WHERE sku_id=88");
            if (crash) { c.abort(Runnable::run); throw new Crash(); }
            c.commit();
            return true;
        }
        if (one(c, "SELECT COUNT(*) FROM confirm_done WHERE xid='" + xid + "' AND branch_id=" + branch) > 0) return true;
        upd(c, "UPDATE stock SET frozen=frozen-2, sold=sold+2 WHERE sku_id=88");   // 自动提交
        if (crash) { c.abort(Runnable::run); throw new Crash(); }
        upd(c, ins);
        return true;
    }
}
