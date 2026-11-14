package labs.arch.distributed_transactions;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 交易靶场 · J09 分布式事务。一个 MySQL 8.4 里建三个库 order_db / inventory_db / coupon_db，
 * 每个“服务”只用连自己库的连接（谁也不碰别人的库），把订单 9527 用四种做法各下一遍：
 *   local    第 4～6 站：三个本地事务各提交各的，发起方死在第 6 站前
 *   window   四种做法（XA、TCC、Saga、本地消息表）× 两种情况，并发买家在第 50 ms 来买 sku 88：
 *              hot   库存 50、券正常：买家要等多久
 *              last2 只剩 2 件、券早被别的单用了（这单注定失败）：买家买到没有，最后三个库什么样
 *   xacrash  XA：协调者死在 PREPARE 之前 / 之后，锁在不在、谁来收拾
 *   fence    第 6、10 站：发起方 kill -9，TC 超时回滚；库存 Cancel 被重发、券的 Cancel 比 Try 先到、
 *            迟到的 Try。不开 fence 和开 fence（照 Seata tcc_fence_log 的逻辑手写）各跑一遍
 * 参数：段名 [JDBC URL 前缀]，默认 jdbc:mysql://127.0.0.1:33095/
 */
public class DtxLab {
    static String base = "jdbc:mysql://127.0.0.1:33095/";
    static final int COUPON_MS = 300;   // 券服务这次很慢：300 ms 才处理完 Try
    static final int BUYER_AT = 50;     // 并发买家在全局事务开始后第 50 ms 来买 1 件 sku 88
    /** Connection.abort 用的执行器：守护线程，跑完不拖住 JVM 退出 */
    static final java.util.concurrent.Executor ABORT = r -> { Thread t = new Thread(r); t.setDaemon(true); t.start(); };

    public static void main(String[] a) throws Exception {
        if (a.length > 1) base = a[1];
        String step = a.length > 0 ? a[0] : "all";
        setup();
        if (step.equals("local") || step.equals("all")) local();
        if (step.equals("window") || step.equals("all")) window();
        if (step.equals("xacrash") || step.equals("all")) xacrash();
        if (step.equals("fence") || step.equals("all")) fence();
    }

    // ---------- 基础 ----------
    static Connection conn(String db) throws SQLException {
        Connection c = DriverManager.getConnection(base + db + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "root");
        exec(c, "SET SESSION innodb_lock_wait_timeout = 5");
        return c;
    }
    static void exec(Connection c, String... sqls) throws SQLException {
        try (Statement s = c.createStatement()) { for (String q : sqls) s.execute(q); }
    }
    static int upd(Connection c, String q) throws SQLException {
        try (Statement s = c.createStatement()) { return s.executeUpdate(q); }
    }
    static String str(Connection c, String q) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(q)) {
            if (!r.next()) return "没有";
            StringBuilder b = new StringBuilder();
            for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) b.append(i > 1 ? " " : "").append(r.getString(i));
            return b.toString();
        }
    }
    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    static final String FENCE_DDL = "CREATE TABLE tcc_fence_log (xid VARCHAR(128) NOT NULL, branch_id BIGINT NOT NULL," +
            " action_name VARCHAR(64) NOT NULL, status TINYINT NOT NULL COMMENT '1 tried 2 committed 3 rollbacked 4 suspended'," +
            " gmt_create DATETIME(3) NOT NULL, gmt_modified DATETIME(3) NOT NULL, PRIMARY KEY (xid, branch_id))";

    static void setup() throws Exception {
        try (Connection c = conn("")) {
            for (String db : new String[]{"order_db", "inventory_db", "coupon_db"})
                exec(c, "DROP DATABASE IF EXISTS " + db, "CREATE DATABASE " + db);
            exec(c, "USE order_db",
                    "CREATE TABLE orders (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(16))",
                    "CREATE TABLE outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT, topic VARCHAR(16), status VARCHAR(8), tries INT DEFAULT 0)",
                    FENCE_DDL,
                    "USE inventory_db",
                    "CREATE TABLE stock (sku_id BIGINT PRIMARY KEY, available INT, frozen INT, sold INT)",
                    "CREATE TABLE consumed (order_id BIGINT PRIMARY KEY)",   // 本地消息表做法里消费端的去重表
                    FENCE_DDL,
                    "USE coupon_db",
                    "CREATE TABLE user_coupon (id BIGINT PRIMARY KEY, user_id BIGINT, code VARCHAR(16), status VARCHAR(8), order_id BIGINT)",
                    FENCE_DDL);
        }
    }

    /** 回到初始状态：sku 88 可售 avail，券 7（MINUS20）状态 couponStatus、属于 couponOrder */
    static void reset(int avail, int frozen, String couponStatus, Long couponOrder) throws SQLException {
        try (Connection c = conn("")) {
            exec(c, "DELETE FROM order_db.orders", "DELETE FROM order_db.outbox", "DELETE FROM order_db.tcc_fence_log",
                    "DELETE FROM inventory_db.stock", "DELETE FROM inventory_db.consumed", "DELETE FROM inventory_db.tcc_fence_log",
                    "DELETE FROM coupon_db.user_coupon", "DELETE FROM coupon_db.tcc_fence_log",
                    "INSERT INTO inventory_db.stock VALUES (88, " + avail + ", " + frozen + ", 0)",
                    "INSERT INTO coupon_db.user_coupon VALUES (7, 1024, 'MINUS20', '" + couponStatus + "', " + couponOrder + ")");
        }
    }

    static String state() throws SQLException {
        try (Connection c = conn("")) {
            return "订单 9527 " + str(c, "SELECT status FROM order_db.orders WHERE id = 9527")
                    + " | sku 88 可售/冻结/已售 " + str(c, "SELECT CONCAT(available,'/',frozen,'/',sold) FROM inventory_db.stock WHERE sku_id = 88")
                    + " | 券 MINUS20 " + str(c, "SELECT CONCAT(status, ' → ', IFNULL(order_id,'-')) FROM coupon_db.user_coupon WHERE id = 7");
        }
    }

    /** 并发买家：第 BUYER_AT ms 时来买 1 件 sku 88，记下等了多久、买到没有 */
    static Future<String> buyer(ExecutorService ex, long t0) {
        return ex.submit(() -> {
            sleep(Math.max(0, BUYER_AT - ms(t0)));
            try (Connection c = conn("inventory_db")) {
                long b0 = System.nanoTime();
                int n = upd(c, "UPDATE stock SET available = available - 1, sold = sold + 1 WHERE sku_id = 88 AND available >= 1");
                return String.format("等了 %3d ms，%s", ms(b0), n == 1 ? "买到了" : "没货，被拒");
            } catch (SQLException e) {
                return "报错 " + e.getErrorCode() + " " + e.getMessage();
            }
        });
    }

    // ---------- local：三个本地事务各提交各的 ----------
    static void local() throws Exception {
        System.out.println("[local] 三个服务各开各的本地事务、各自提交；订单服务在第 6 站（调券）之前被 kill -9");
        reset(50, 0, "UNUSED", null);
        try (Connection o = conn("order_db"); Connection s = conn("inventory_db")) {
            upd(o, "INSERT INTO orders VALUES (9527, 1024, 'WAIT_PAY')");
            upd(s, "UPDATE stock SET available = available - 2, sold = sold + 2 WHERE sku_id = 88 AND available >= 2");
            // kill -9：券服务再也收不到请求，也没人去把前两笔改回来
        }
        System.out.println("  结果：" + state());
        System.out.println("  订单待支付、库存扣了，券却没用：用户付 20 元的差价从哪来？没有任何一方知道要回滚");
    }

    // ---------- window：四种做法谁锁资源多久、谁的中间态被别人看见 ----------
    interface Scheme { boolean run(boolean couponWillFail) throws Exception; }

    static void window() throws Exception {
        Map<String, Scheme> schemes = new LinkedHashMap<>();
        schemes.put("XA 两阶段提交", DtxLab::xa);
        schemes.put("TCC（带 fence）", f -> tcc());
        schemes.put("Saga（正向 + 补偿）", f -> saga());
        schemes.put("本地消息表", f -> outbox());
        String[][] cases = {
                {"hot", "热门款：可售 50，券正常 → 这单会成功"},
                {"last2", "最后 2 件：可售 2，券早被 9400 用了 → 这单注定失败"}};
        for (String[] cs : cases) {
            System.out.println("[window] " + cs[1] + "；券服务 " + COUPON_MS + " ms 才处理完；买家第 " + BUYER_AT + " ms 来买 1 件 sku 88");
            for (Map.Entry<String, Scheme> e : schemes.entrySet()) {
                boolean hot = cs[0].equals("hot");
                reset(hot ? 50 : 2, 0, hot ? "UNUSED" : "USED", hot ? null : 9400L);
                ExecutorService ex = Executors.newSingleThreadExecutor();
                long t0 = System.nanoTime();
                Future<String> b = buyer(ex, t0);
                boolean ok = e.getValue().run(!hot);
                long total = ms(t0);
                String br = b.get();
                ex.shutdown();
                System.out.printf("  %-14s 这单%s（%d ms）｜买家%s｜%s%n", e.getKey(), ok ? "成功" : "失败", total, br, state());
            }
        }
    }

    static void xa(Connection c, String verb, String branch) throws SQLException {
        exec(c, "XA " + verb + " '9527','" + branch + "'");
    }

    /** XA：三个分支的写都在各自的 XA 事务里，行锁一直拿到 XA COMMIT / ROLLBACK */
    static boolean xa(boolean couponWillFail) throws Exception {
        try (Connection o = conn("order_db"); Connection s = conn("inventory_db"); Connection c = conn("coupon_db")) {
            xa(o, "START", "order");
            int n1 = upd(o, "INSERT INTO orders VALUES (9527, 1024, 'WAIT_PAY')");
            xa(o, "END", "order");
            xa(s, "START", "stock");
            int n2 = upd(s, "UPDATE stock SET available = available - 2, sold = sold + 2 WHERE sku_id = 88 AND available >= 2");
            xa(s, "END", "stock");
            sleep(COUPON_MS);
            xa(c, "START", "coupon");
            int n3 = upd(c, "UPDATE user_coupon SET status = 'USED', order_id = 9527 WHERE id = 7 AND status = 'UNUSED'");
            xa(c, "END", "coupon");
            boolean ok = n1 == 1 && n2 == 1 && n3 == 1;
            if (ok) {   // 阶段一：都 PREPARE 成功；阶段二：都 COMMIT
                xa(o, "PREPARE", "order"); xa(s, "PREPARE", "stock"); xa(c, "PREPARE", "coupon");
                xa(o, "COMMIT", "order"); xa(s, "COMMIT", "stock"); xa(c, "COMMIT", "coupon");
            } else {    // 有人投了反对票：全部回滚，数据库替你把行改回去
                xa(o, "ROLLBACK", "order"); xa(s, "ROLLBACK", "stock"); xa(c, "ROLLBACK", "coupon");
            }
            return ok;
        }
    }

    // ---- TCC 三个参与者（fence = true 时照 Seata TCCFenceHandler 的逻辑记 tcc_fence_log）----
    static final String XID = "10.0.0.5:8091:9527";

    /** fence 版的 Try：fence 记录和业务同一个本地事务；fence 插不进去（Cancel 已经来过）就拒绝 */
    static boolean fenceTry(Connection c, long branch, String action, String biz) throws SQLException {
        c.setAutoCommit(false);
        try {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO tcc_fence_log VALUES (?, ?, ?, 1, NOW(3), NOW(3))")) {
                p.setString(1, XID); p.setLong(2, branch); p.setString(3, action); p.executeUpdate();
            }
            int n = upd(c, biz);
            if (n != 1) { c.rollback(); return false; }
            c.commit();
            return true;
        } catch (SQLIntegrityConstraintViolationException dup) {
            c.rollback();
            throw new IllegalStateException("fence 已有记录，拒绝执行 Try（防悬挂）");
        } finally { c.setAutoCommit(true); }
    }

    /** fence 版的二阶段：commit=true 是 Confirm，false 是 Cancel。返回 TC 收到的“成功/失败” */
    static String fencePhase2(Connection c, long branch, String action, boolean commit, String biz) throws SQLException {
        c.setAutoCommit(false);
        try {
            Integer st = null;
            try (PreparedStatement p = c.prepareStatement("SELECT status FROM tcc_fence_log WHERE xid = ? AND branch_id = ? FOR UPDATE")) {
                p.setString(1, XID); p.setLong(2, branch);
                try (ResultSet r = p.executeQuery()) { if (r.next()) st = r.getInt(1); }
            }
            String ret;
            if (st == null) {
                if (commit) throw new IllegalStateException("fence 没有 Try 记录，Confirm 抛异常");
                else {   // 空回滚：Try 没来过。插一行 suspended，什么业务也不改
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO tcc_fence_log VALUES (?, ?, ?, 4, NOW(3), NOW(3))")) {
                        p.setString(1, XID); p.setLong(2, branch); p.setString(3, action); p.executeUpdate();
                    }
                    ret = "成功：空回滚，记一行 suspended";
                }
            } else if (st == (commit ? 2 : 3) || (!commit && st == 4)) {
                ret = "成功：已经做过，直接返回（幂等）";
            } else if (st != 1) {
                ret = "失败：状态是 " + st + "，不能再" + (commit ? "提交" : "回滚");
            } else {
                upd(c, biz);
                upd(c, "UPDATE tcc_fence_log SET status = " + (commit ? 2 : 3) + ", gmt_modified = NOW(3) WHERE xid = '" + XID + "' AND branch_id = " + branch);
                ret = "成功：执行了业务";
            }
            c.commit();
            return ret;
        } finally { c.setAutoCommit(true); }
    }

    static final String ORDER_TRY = "INSERT INTO orders VALUES (9527, 1024, 'CREATING')";
    static final String ORDER_CONFIRM = "UPDATE orders SET status = 'WAIT_PAY' WHERE id = 9527 AND status = 'CREATING'";
    static final String ORDER_CANCEL = "UPDATE orders SET status = 'CANCELLED' WHERE id = 9527 AND status = 'CREATING'";
    static final String STOCK_TRY = "UPDATE stock SET available = available - 2, frozen = frozen + 2 WHERE sku_id = 88 AND available >= 2";
    static final String STOCK_CONFIRM = "UPDATE stock SET frozen = frozen - 2, sold = sold + 2 WHERE sku_id = 88 AND frozen >= 2";
    static final String STOCK_CANCEL = "UPDATE stock SET available = available + 2, frozen = frozen - 2 WHERE sku_id = 88 AND frozen >= 2";
    static final String COUPON_TRY = "UPDATE user_coupon SET status = 'LOCKED', order_id = 9527 WHERE id = 7 AND status = 'UNUSED'";
    static final String COUPON_CONFIRM = "UPDATE user_coupon SET status = 'USED' WHERE id = 7 AND status = 'LOCKED' AND order_id = 9527";
    static final String COUPON_CANCEL = "UPDATE user_coupon SET status = 'UNUSED', order_id = NULL WHERE id = 7 AND status = 'LOCKED' AND order_id = 9527";

    /** TCC：Try 各自本地提交（资源只冻结），全部 Try 成功再 Confirm，否则 Cancel */
    static boolean tcc() throws Exception {
        try (Connection o = conn("order_db"); Connection s = conn("inventory_db"); Connection c = conn("coupon_db")) {
            boolean t1 = fenceTry(o, 1, "order", ORDER_TRY);
            boolean t2 = t1 && fenceTry(s, 2, "stock", STOCK_TRY);
            sleep(COUPON_MS);
            boolean t3 = t2 && fenceTry(c, 3, "coupon", COUPON_TRY);
            boolean ok = t1 && t2 && t3;
            fencePhase2(o, 1, "order", ok, ok ? ORDER_CONFIRM : ORDER_CANCEL);
            fencePhase2(s, 2, "stock", ok, ok ? STOCK_CONFIRM : STOCK_CANCEL);
            fencePhase2(c, 3, "coupon", ok, ok ? COUPON_CONFIRM : COUPON_CANCEL);
            return ok;
        }
    }

    /** Saga：每一步直接做成、本地提交；失败时按相反顺序跑补偿 */
    static boolean saga() throws Exception {
        try (Connection o = conn("order_db"); Connection s = conn("inventory_db"); Connection c = conn("coupon_db")) {
            upd(o, "INSERT INTO orders VALUES (9527, 1024, 'PENDING')");
            if (upd(s, "UPDATE stock SET available = available - 2, sold = sold + 2 WHERE sku_id = 88 AND available >= 2") != 1) {
                upd(o, "UPDATE orders SET status = 'CANCELLED' WHERE id = 9527");
                return false;
            }
            sleep(COUPON_MS);
            if (upd(c, "UPDATE user_coupon SET status = 'USED', order_id = 9527 WHERE id = 7 AND status = 'UNUSED'") != 1) {
                upd(s, "UPDATE stock SET available = available + 2, sold = sold - 2 WHERE sku_id = 88");   // 补偿：库存加回去
                upd(o, "UPDATE orders SET status = 'CANCELLED' WHERE id = 9527");                          // 补偿：订单取消
                return false;
            }
            upd(o, "UPDATE orders SET status = 'WAIT_PAY' WHERE id = 9527");
            return true;
        }
    }

    /** 本地消息表：订单和两条待发消息一个本地事务提交；投递任务把消息送到库存、券服务，失败就重试 */
    static boolean outbox() throws Exception {
        try (Connection o = conn("order_db"); Connection s = conn("inventory_db"); Connection c = conn("coupon_db")) {
            o.setAutoCommit(false);
            upd(o, "INSERT INTO orders VALUES (9527, 1024, 'WAIT_PAY')");
            upd(o, "INSERT INTO outbox (order_id, topic, status) VALUES (9527, 'STOCK', 'NEW'), (9527, 'COUPON', 'NEW')");
            o.commit();
            o.setAutoCommit(true);
            // 投递任务（这里同步跑，真实里是另一个线程每秒扫一次）
            boolean allSent = true;
            for (String topic : new String[]{"STOCK", "COUPON"}) {
                boolean done = false;
                for (int tries = 1; tries <= 3 && !done; tries++) {
                    if (topic.equals("STOCK")) {
                        s.setAutoCommit(false);   // 消费端：去重表和扣库存一个本地事务
                        try {
                            upd(s, "INSERT INTO consumed VALUES (9527)");
                            done = upd(s, "UPDATE stock SET available = available - 2, sold = sold + 2 WHERE sku_id = 88 AND available >= 2") == 1;
                            if (done) s.commit(); else s.rollback();
                        } catch (SQLIntegrityConstraintViolationException dup) { s.rollback(); done = true; }
                        s.setAutoCommit(true);
                    } else {
                        if (tries == 1) sleep(COUPON_MS);
                        done = upd(c, "UPDATE user_coupon SET status = 'USED', order_id = 9527 WHERE id = 7 AND status = 'UNUSED'") == 1;
                    }
                    upd(o, "UPDATE outbox SET tries = " + tries + ", status = '" + (done ? "SENT" : tries == 3 ? "DEAD" : "NEW")
                            + "' WHERE order_id = 9527 AND topic = '" + topic + "'");
                    if (!done) sleep(20);
                }
                allSent &= done;
            }
            return allSent;
        }
    }

    // ---------- xacrash：协调者死在 PREPARE 前 / 后 ----------
    static void xacrash() throws Exception {
        System.out.println("[xacrash] XA：协调者（订单服务）在两个时刻被 kill -9（Connection.abort 掐断连接），买家随后来买 sku 88，锁等待上限 2 秒");
        try (Connection admin = conn("")) {
            System.out.println("  xa_detach_on_prepare = " + str(admin, "SELECT @@global.xa_detach_on_prepare") + "，版本 " + str(admin, "SELECT VERSION()"));
        }
        // A：库存分支做完 UPDATE、还没 PREPARE
        reset(50, 0, "UNUSED", null);
        Connection s = conn("inventory_db");
        xa(s, "START", "stock");
        upd(s, "UPDATE stock SET available = available - 2, sold = sold + 2 WHERE sku_id = 88");
        s.abort(ABORT);
        sleep(200);
        try (Connection admin = conn("")) {
            System.out.println("  A 死在 PREPARE 之前：XA RECOVER 列出 " + countRecover(admin) + " 个分支｜买家" + buyNow(2) + "｜" + state());
        }
        // B：三个分支都 PREPARE 了，还没 COMMIT
        reset(50, 0, "UNUSED", null);
        Connection o = conn("order_db"), s2 = conn("inventory_db"), c = conn("coupon_db");
        xa(o, "START", "order"); upd(o, "INSERT INTO orders VALUES (9527, 1024, 'WAIT_PAY')"); xa(o, "END", "order"); xa(o, "PREPARE", "order");
        xa(s2, "START", "stock"); upd(s2, "UPDATE stock SET available = available - 2, sold = sold + 2 WHERE sku_id = 88"); xa(s2, "END", "stock"); xa(s2, "PREPARE", "stock");
        xa(c, "START", "coupon"); upd(c, "UPDATE user_coupon SET status = 'USED', order_id = 9527 WHERE id = 7"); xa(c, "END", "coupon"); xa(c, "PREPARE", "coupon");
        for (Connection x : new Connection[]{o, s2, c}) x.abort(ABORT);
        sleep(200);
        try (Connection admin = conn("")) {
            System.out.println("  B 死在 PREPARE 之后：XA RECOVER 列出 " + countRecover(admin) + " 个分支｜买家" + buyNow(2));
            System.out.println("    此刻读到的（读已提交）：" + state());
            // 协调者重启，翻自己的日志：没写过“提交”决议 → 回滚。换一个全新的连接也能收尾
            for (String b : new String[]{"order", "stock", "coupon"}) xa(admin, "ROLLBACK", b);
            System.out.println("  协调者恢复后用新连接 XA ROLLBACK 三个分支：XA RECOVER 剩 " + countRecover(admin) + " 个｜买家" + buyNow(2) + "｜" + state());
        }
    }

    static int countRecover(Connection c) throws SQLException {
        int n = 0;
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("XA RECOVER")) { while (r.next()) n++; }
        return n;
    }

    static String buyNow(int lockWaitSec) {
        try (Connection c = conn("inventory_db")) {
            exec(c, "SET SESSION innodb_lock_wait_timeout = " + lockWaitSec);
            long b0 = System.nanoTime();
            try {
                int n = upd(c, "UPDATE stock SET available = available - 1, sold = sold + 1 WHERE sku_id = 88 AND available >= 1");
                return String.format("等了 %d ms，%s", ms(b0), n == 1 ? "买到了" : "没货");
            } catch (SQLException e) {
                return String.format("等了 %d ms，报错 %d（%s）", ms(b0), e.getErrorCode(), e.getMessage());
            }
        } catch (SQLException e) { return "连接失败 " + e.getMessage(); }
    }

    // ---------- fence：第 6 站 kill -9 之后，TC 超时回滚的那一串乱序请求 ----------
    static void fence() throws Exception {
        System.out.println("[fence] 背景：订单 9528 已经冻结了 sku 88 的 2 件（它的全局事务还在走）。9527：订单 Try、库存 Try 成功，");
        System.out.println("        券分支已登记、Try 请求还在路上，这时订单服务 kill -9。TC 等到超时（真实 60 秒，这里直接触发）开始回滚：");
        for (boolean useFence : new boolean[]{false, true}) {
            reset(48, 2, "UNUSED", null);
            System.out.println("  —— " + (useFence ? "开 fence（照 Seata tcc_fence_log）" : "不开 fence：Try/Confirm/Cancel 就是三条 SQL") + " ——");
            try (Connection o = conn("order_db"); Connection s = conn("inventory_db"); Connection c = conn("coupon_db")) {
                if (useFence) {   // 9528 的库存分支也是开 fence 的
                    exec(s, "INSERT INTO tcc_fence_log VALUES ('10.0.0.5:8091:9528', 2, 'stock', 1, NOW(3), NOW(3))");
                }
                step(useFence, o, 1, "order", true, ORDER_TRY, "订单 Try");
                step(useFence, s, 2, "stock", true, STOCK_TRY, "库存 Try");
                System.out.println("     ✕ kill -9 订单服务；TC 超时 → 给三个分支推 Cancel");
                phase2(useFence, o, 1, "order", false, ORDER_CANCEL, "订单 Cancel");
                phase2(useFence, s, 2, "stock", false, STOCK_CANCEL, "库存 Cancel（第 1 次，应答丢了）");
                phase2(useFence, s, 2, "stock", false, STOCK_CANCEL, "库存 Cancel（TC 重发）");
                phase2(useFence, c, 3, "coupon", false, COUPON_CANCEL, "券 Cancel（Try 还没到）");
                step(useFence, c, 3, "coupon", true, COUPON_TRY, "券 Try（迟到）");
                // 9528 那边一切正常，它的 TC 推 Confirm
                String r9528;
                if (useFence) {
                    s.setAutoCommit(false);
                    String st = str(s, "SELECT status FROM tcc_fence_log WHERE xid = '10.0.0.5:8091:9528' FOR UPDATE");
                    int n = st.equals("1") ? upd(s, STOCK_CONFIRM) : 0;
                    if (n == 1) upd(s, "UPDATE tcc_fence_log SET status = 2 WHERE xid = '10.0.0.5:8091:9528'");
                    s.commit(); s.setAutoCommit(true);
                    r9528 = n == 1 ? "成功" : "失败";
                } else r9528 = upd(s, STOCK_CONFIRM) == 1 ? "成功" : "失败（冻结量不够了）";
                System.out.println("     9528 的库存 Confirm：" + r9528 + "｜" + state());
            }
        }
    }

    static void step(boolean useFence, Connection c, long branch, String action, boolean isTry, String sql, String label) throws SQLException {
        String r;
        try {
            if (useFence) r = fenceTry(c, branch, action, sql) ? "成功" : "失败";
            else r = upd(c, sql) == 1 ? "成功（改了 1 行）" : "失败（改了 0 行）";
        } catch (IllegalStateException e) { r = "被拒：" + e.getMessage(); }
        System.out.printf("     %-22s %s｜%s%n", label, r, state());
    }

    static void phase2(boolean useFence, Connection c, long branch, String action, boolean commit, String sql, String label) throws SQLException {
        String r = useFence ? fencePhase2(c, branch, action, commit, sql) : "改了 " + upd(c, sql) + " 行，返回成功";
        System.out.printf("     %-22s %s｜%s%n", label, r, state());
    }
}
