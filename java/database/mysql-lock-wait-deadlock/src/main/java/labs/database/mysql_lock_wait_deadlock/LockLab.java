package labs.database.mysql_lock_wait_deadlock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 交易靶场 orders 表（100 万行，和全景 J06、索引页、执行计划页同一份数据），看两个事务抢同一行时发生了什么：
 *   setup     建表、灌数、建索引，再建一张 payments（扣款流水）
 *   pay       用户连点两次付款：先查后改 / 条件更新 / SELECT … FOR UPDATE 三种写法，各扣了几次款，第二个等了多久
 *   locks     三种 WHERE（主键 / idx_user / 没索引的 sku_id）在 RR 和 RC 下各锁了多少，另一个会话哪些操作被挡
 *   wait      锁等待超时 1205：只回滚那一条语句，事务还开着；sys.innodb_lock_waits 里看到的样子
 *   deadlock  两笔批量操作按相反顺序改 9527 和 9530：谁被回滚、死锁日志长什么样
 * 由 run.sh 调用；JDBC 地址默认 127.0.0.1:33073，可用 -Durl=... 覆盖。按 JDK 8 写法（--release 8）。
 */
public class LockLab {
    static final String URL = System.getProperty("url",
            "jdbc:mysql://127.0.0.1:33073/trade?user=root&password=root&allowPublicKeyRetrieval=true&useSSL=false");
    static final ExecutorService POOL = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    static final int HOLD_MS = 200;   // 第一笔付款拿着锁的时间：模拟事务里还做了别的事

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "pay";
        if ("setup".equals(cmd)) setup();
        else if ("pay".equals(cmd)) pay();
        else if ("locks".equals(cmd)) { locks(Connection.TRANSACTION_REPEATABLE_READ); locks(Connection.TRANSACTION_READ_COMMITTED); }
        else if ("wait".equals(cmd)) waitTimeout();
        else if ("deadlock".equals(cmd)) deadlock();
        else throw new IllegalArgumentException(cmd);
    }

    /** 一条手动提交的连接；锁等待上限设成 1 秒，免得被挡时干等 50 秒 */
    static Connection tx(int iso) throws Exception {
        Connection c = DriverManager.getConnection(URL);
        c.setTransactionIsolation(iso);
        try (Statement s = c.createStatement()) { s.execute("SET SESSION innodb_lock_wait_timeout = 1"); }
        c.setAutoCommit(false);
        return c;
    }
    static Connection tx() throws Exception { return tx(Connection.TRANSACTION_REPEATABLE_READ); }

    static String one(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.next() ? r.getString(1) : null; }
    }
    static int upd(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) { return s.executeUpdate(sql); }
    }
    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }

    static void reset() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("UPDATE orders SET status='CREATED', paid_at=NULL WHERE id IN (9527, 9530)");
            s.execute("DELETE FROM orders WHERE id > 1000000");
            s.execute("TRUNCATE TABLE payments");
        }
    }

    // ------------------------------------------------------------------ setup
    static void setup() throws Exception {
        String root = URL.replace("/trade?", "/?");
        try (Connection c = DriverManager.getConnection(root); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS trade");
            s.execute("USE trade");
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("CREATE TABLE orders ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " order_no VARCHAR(20) NOT NULL,"
                    + " user_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, qty INT NOT NULL,"
                    + " amount_cents BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
                    + " created_at DATETIME(6) NOT NULL, paid_at DATETIME(6) NULL) ENGINE=InnoDB");
            s.execute("SET SESSION cte_max_recursion_depth = 1000000");
            long t0 = System.nanoTime();
            for (int b = 0; b < 10; b++) {           // 100 万行，和执行计划页同一份数据
                int o = b * 100000;
                String id = "(" + o + " + i)";
                s.execute("INSERT INTO orders WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100000)"
                        + " SELECT " + id + ", '', 1 + " + id + " * 7919 % 200000, 1 + " + id + " % 500,"
                        + " 1 + " + id + " % 3, 1000 + " + id + " * 37 % 99000,"
                        + " IF(" + id + " % 10 = 0, 'CREATED', 'PAID'),"
                        + " TIMESTAMP('2026-11-01') + INTERVAL " + id + " SECOND, NULL FROM n");
            }
            s.execute("UPDATE orders SET order_no = CONCAT(DATE_FORMAT(created_at, '%Y%m%d'), LPAD(id, 8, '0'))");
            s.execute("UPDATE orders SET user_id=1024, sku_id=88, qty=2, amount_cents=79800, status='CREATED' WHERE id=9527");
            s.execute("ALTER TABLE orders ADD UNIQUE KEY uk_order_no (order_no), ADD KEY idx_user (user_id), ADD KEY idx_created (created_at)");
            s.execute("ANALYZE TABLE orders");
            s.execute("DROP TABLE IF EXISTS payments");
            s.execute("CREATE TABLE payments (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL,"
                    + " amount_cents BIGINT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), KEY idx_order (order_id)) ENGINE=InnoDB");
            System.out.printf("[setup] 100 万行 + 3 个二级索引 + payments，用时 %.1f s%n", (System.nanoTime() - t0) / 1e9);
            try (ResultSet r = s.executeQuery("SELECT GROUP_CONCAT(id ORDER BY id) FROM orders WHERE user_id = 1024")) {
                r.next();
                System.out.println("[setup] user_id=1024 的订单：" + r.getString(1));
            }
        }
    }

    // ------------------------------------------------------------------ pay
    static final String PAY_SQL = "INSERT INTO payments(order_id, amount_cents) VALUES (9527, 79800)";

    static void pay() throws Exception {
        System.out.println("[pay] 用户 1024 连点两次付款，两个请求 A、B 几乎同时到；A 拿着锁 " + HOLD_MS + " ms 后提交。RR（默认）。");
        // 写法 1：先普通 SELECT 看状态，是 CREATED 就改、就扣款
        reset();
        try (Connection a = tx(); Connection b = tx()) {
            String sa = one(a, "SELECT status FROM orders WHERE id=9527");
            String sb = one(b, "SELECT status FROM orders WHERE id=9527");
            int ra = upd(a, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527");
            upd(a, PAY_SQL);
            long t0 = System.nanoTime();
            Future<Integer> fb = POOL.submit(() -> upd(b, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527"));
            Thread.sleep(HOLD_MS);
            a.commit();
            int rb = fb.get();
            double waited = ms(t0);
            if ("CREATED".equals(sb)) upd(b, PAY_SQL);
            b.commit();
            System.out.printf("  写法 1 先查后改   A 读到 %s 改了 %d 行 | B 读到 %s，UPDATE 等了 %.0f ms，改了 %d 行 → 扣款 %s 次%n",
                    sa, ra, sb, waited, rb, count("payments"));
        }
        // 写法 2：条件更新，WHERE 里带上 status='CREATED'，按影响行数决定扣不扣
        reset();
        try (Connection a = tx(); Connection b = tx()) {
            String sb = one(b, "SELECT status FROM orders WHERE id=9527");   // B 先看了一眼（拍下了快照）
            int ra = upd(a, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'");
            if (ra == 1) upd(a, PAY_SQL);
            long t0 = System.nanoTime();
            Future<Integer> fb = POOL.submit(() -> upd(b, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'"));
            Thread.sleep(HOLD_MS);
            a.commit();
            int rb = fb.get();
            double waited = ms(t0);
            if (rb == 1) upd(b, PAY_SQL);
            String snap = one(b, "SELECT status FROM orders WHERE id=9527");
            String cur = one(b, "SELECT status FROM orders WHERE id=9527 FOR SHARE");
            b.commit();
            System.out.printf("  写法 2 条件更新   A 改了 %d 行 | B 先读到 %s，UPDATE 等了 %.0f ms，改了 %d 行 → 扣款 %s 次%n",
                    ra, sb, waited, rb, count("payments"));
            System.out.printf("                    B 事务里接着读：普通 SELECT 读到 %s，SELECT … FOR SHARE 读到 %s%n", snap, cur);
        }
        // 写法 3：SELECT … FOR UPDATE 先锁住再判断
        reset();
        try (Connection a = tx(); Connection b = tx()) {
            String sa = one(a, "SELECT status FROM orders WHERE id=9527 FOR UPDATE");
            if ("CREATED".equals(sa)) { upd(a, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527"); upd(a, PAY_SQL); }
            long t0 = System.nanoTime();
            Future<String> fb = POOL.submit(() -> one(b, "SELECT status FROM orders WHERE id=9527 FOR UPDATE"));
            Thread.sleep(HOLD_MS);
            a.commit();
            String sb = fb.get();
            double waited = ms(t0);
            if ("CREATED".equals(sb)) { upd(b, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527"); upd(b, PAY_SQL); }
            b.commit();
            System.out.printf("  写法 3 FOR UPDATE A 读到 %s | B 的 FOR UPDATE 等了 %.0f ms，读到 %s → 扣款 %s 次%n",
                    sa, waited, sb, count("payments"));
        }
    }

    static String count(String table) throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) { return one(c, "SELECT COUNT(*) FROM " + table); }
    }

    // ------------------------------------------------------------------ locks
    static void locks(int iso) throws Exception {
        String name = iso == Connection.TRANSACTION_READ_COMMITTED ? "RC" : "RR";
        String[][] cases = {
                {"主键", "UPDATE orders SET status='PAID' WHERE id=9527 AND status='CREATED'"},
                {"idx_user", "UPDATE orders SET status='PAID' WHERE user_id=1024 AND status='CREATED'"},
                {"没索引", "UPDATE orders SET status='PAID' WHERE sku_id=88 AND status='CREATED'"},
        };
        String[][] probes = {
                {"改 9528（隔壁一单）", "UPDATE orders SET paid_at=NOW(6) WHERE id=9528"},
                {"改 10（无关的一单）", "UPDATE orders SET paid_at=NOW(6) WHERE id=10"},
                {"插一单 user 1024", ins(1000001, 1024)},
                {"插一单 user 1023", ins(1000002, 1023)},
                {"普通 SELECT 9527", "SELECT status FROM orders WHERE id=9527"},
        };
        for (String[] k : cases) {
            reset();
            try (Connection a = tx(iso); Connection b = tx(iso)) {
                long t0 = System.nanoTime();
                int rows = upd(a, k[1]);
                double cost = ms(t0);
                String trx; String info;
                Thread.sleep(150);   // information_schema.innodb_trx 是一份最多 0.1 秒刷新一次的缓存，刚读过就再读会读到旧的
                try (Statement s = a.createStatement(); ResultSet r = s.executeQuery(
                        "SELECT trx_id, trx_rows_locked, trx_lock_structs, trx_lock_memory_bytes FROM information_schema.innodb_trx"
                                + " WHERE trx_mysql_thread_id = CONNECTION_ID()")) {
                    if (!r.next()) {   // 没改到行、锁也都放掉了：InnoDB 里查不到这个事务
                        System.out.printf("[locks %s] %-8s %s%n    改了 %d 行，用时 %.0f ms，innodb_trx 里查不到这个事务%n", name, k[0], k[1], rows, cost);
                        a.rollback();
                        continue;
                    }
                    trx = r.getString(1);
                    info = String.format("trx_rows_locked=%s lock_structs=%s 锁内存=%.1f KB",
                            r.getString(2), r.getString(3), r.getLong(4) / 1024.0);
                }
                System.out.printf("[locks %s] %-8s %s%n    改了 %d 行，用时 %.0f ms，%s%n", name, k[0], k[1], rows, cost, info);
                long t1 = System.nanoTime();
                StringBuilder sb = new StringBuilder();
                try (Statement s = b.createStatement(); ResultSet r = s.executeQuery(
                        "SELECT IFNULL(INDEX_NAME,'(表)'), LOCK_MODE, COUNT(*), MIN(LOCK_DATA), MAX(LOCK_DATA) FROM performance_schema.data_locks"
                                + " WHERE ENGINE_TRANSACTION_ID = " + trx + " GROUP BY 1, 2 ORDER BY 1, 2")) {
                    while (r.next()) sb.append(String.format("      %-9s %-14s %8d 把  例：%s%s%n", r.getString(1), r.getString(2), r.getLong(3),
                            r.getString(4), r.getLong(3) > 1 ? " … " + r.getString(5) : ""));
                }
                b.commit();
                System.out.printf("    data_locks（查询用时 %.0f ms）：%n%s", ms(t1), sb);
                if (!"没索引".equals(k[0])) {
                    try (Statement s = b.createStatement(); ResultSet r = s.executeQuery(
                            "SELECT INDEX_NAME, LOCK_MODE, LOCK_DATA FROM performance_schema.data_locks WHERE ENGINE_TRANSACTION_ID = " + trx
                                    + " AND LOCK_TYPE='RECORD' ORDER BY INDEX_NAME, LOCK_DATA")) {
                        StringBuilder all = new StringBuilder("      逐条：");
                        while (r.next()) all.append(r.getString(1)).append(' ').append(r.getString(2)).append(' ').append(r.getString(3)).append(" | ");
                        System.out.println(all);
                    }
                    b.commit();
                }
                StringBuilder pr = new StringBuilder("    另一个会话：");
                for (String[] p : probes) {
                    long t2 = System.nanoTime();
                    String res;
                    try {
                        if (p[1].startsWith("SELECT")) one(b, p[1]); else upd(b, p[1]);
                        res = String.format("通过 %.0fms", ms(t2));
                    } catch (SQLException e) {
                        res = String.format("被挡 %d（%.0fms）", e.getErrorCode(), ms(t2));
                    }
                    b.rollback();
                    pr.append(p[0]).append(' ').append(res).append("；");
                }
                System.out.println(pr);
                a.rollback();
            }
        }
    }

    static String ins(long id, long user) {
        return "INSERT INTO orders VALUES (" + id + ", 'N" + id + "', " + user + ", 88, 1, 100, 'CREATED', NOW(6), NULL)";
    }

    // ------------------------------------------------------------------ wait
    static void waitTimeout() throws Exception {
        reset();
        try (Connection a = tx(); Connection b = tx(); Connection o = DriverManager.getConnection(URL)) {
            System.out.println("[wait] innodb_rollback_on_timeout = " + one(o, "SELECT @@innodb_rollback_on_timeout")
                    + "，全局 innodb_lock_wait_timeout = " + one(o, "SELECT @@global.innodb_lock_wait_timeout") + " 秒（本实验的会话设成 1 秒）");
            upd(a, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'");   // A 付款，迟迟不提交
            upd(b, "UPDATE orders SET status='CANCELLED' WHERE id=9530 AND status='CREATED'");             // B 批量取消：先取消 9530
            long t0 = System.nanoTime();
            Future<String> fb = POOL.submit(new Callable<String>() {
                public String call() throws Exception {
                    try { upd(b, "UPDATE orders SET status='CANCELLED' WHERE id=9527 AND status='CREATED'"); return "成功"; }
                    catch (SQLException e) { return e.getClass().getSimpleName() + " " + e.getErrorCode() + " SQLState=" + e.getSQLState() + "：" + e.getMessage(); }
                }
            });
            Thread.sleep(300);
            try (Statement s = o.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT wait_age, locked_index, locked_type, waiting_query, blocking_pid, sql_kill_blocking_connection FROM sys.innodb_lock_waits")) {
                while (r.next()) System.out.printf("  等的时候 sys.innodb_lock_waits：wait_age=%s 锁在 %s（%s）等的语句：%s%n    挡路的连接 %s，建议 %s%n",
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6));
            }
            String res = fb.get();
            System.out.printf("  B 第二条语句等了 %.0f ms：%s%n", ms(t0), res);
            System.out.println("  报错之后 B 的事务：" + trxOf(o, b));
            b.commit();
            a.commit();
            System.out.println("  B 直接 COMMIT 后：9530 = " + one(o, "SELECT status FROM orders WHERE id=9530")
                    + "，9527 = " + one(o, "SELECT status FROM orders WHERE id=9527") + "  ← 批量取消只做了一半");
        }
    }

    static String trxOf(Connection o, Connection who) throws Exception {
        Thread.sleep(150);   // 等 innodb_trx 的缓存过期
        String id = one(who, "SELECT CONNECTION_ID()");
        try (Statement s = o.createStatement(); ResultSet r = s.executeQuery(
                "SELECT trx_state, trx_rows_modified, trx_rows_locked FROM information_schema.innodb_trx WHERE trx_mysql_thread_id = " + id)) {
            return r.next() ? "还开着 state=" + r.getString(1) + " rows_modified=" + r.getString(2) + " rows_locked=" + r.getString(3) : "已经没有了（整个回滚）";
        }
    }

    // ------------------------------------------------------------------ deadlock
    static void deadlock() throws Exception {
        reset();
        try (Connection a = tx(); Connection b = tx(); Connection o = DriverManager.getConnection(URL)) {
            try (Statement s = a.createStatement()) { s.execute("SET SESSION innodb_lock_wait_timeout = 50"); }
            try (Statement s = b.createStatement()) { s.execute("SET SESSION innodb_lock_wait_timeout = 50"); }
            System.out.println("[deadlock] innodb_deadlock_detect = " + one(o, "SELECT @@innodb_deadlock_detect")
                    + "，A 合并付款按 9527 → 9530，B 批量取消按 9530 → 9527；等锁上限改回 50 秒");
            upd(a, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'");
            upd(b, "UPDATE orders SET status='CANCELLED' WHERE id=9530 AND status='CREATED'");
            long t0 = System.nanoTime();
            Future<String> fa = POOL.submit(() -> attempt(a, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9530 AND status='CREATED'"));
            Thread.sleep(200);
            long t1 = System.nanoTime();
            String rb = attempt(b, "UPDATE orders SET status='CANCELLED' WHERE id=9527 AND status='CREATED'");
            double bms = ms(t1);
            String ra = fa.get();
            double ams = ms(t0);
            System.out.printf("  A 的第二条（先发，等了 %.0f ms）：%s%n  B 的第二条（后发，%.1f ms 返回）：%s%n", ams, ra, bms, rb);
            System.out.println("  报错之后 A：" + trxOf(o, a) + "\n  报错之后 B：" + trxOf(o, b));
            a.commit(); b.commit();
            System.out.println("  都提交后：9527 = " + one(o, "SELECT status FROM orders WHERE id=9527")
                    + "，9530 = " + one(o, "SELECT status FROM orders WHERE id=9530"));
            String st;
            try (Statement s = o.createStatement(); ResultSet r = s.executeQuery("SHOW ENGINE INNODB STATUS")) { r.next(); st = r.getString(3); }
            int i = st.indexOf("LATEST DETECTED DEADLOCK"), j = st.indexOf("------------\nTRANSACTIONS");
            System.out.println("  SHOW ENGINE INNODB STATUS 里的 LATEST DETECTED DEADLOCK：");
            for (String line : st.substring(i, j > i ? j : st.length()).split("\n")) {
                if (line.startsWith("RECORD LOCKS") || line.startsWith("*** ") || line.startsWith("UPDATE") || line.contains("lock_mode")
                        || line.startsWith("TRANSACTION") || line.startsWith("20") || line.startsWith("MySQL thread")) System.out.println("    " + line);
            }
        }
    }

    static String attempt(Connection c, String sql) {
        try { return "成功，改了 " + upd(c, sql) + " 行"; }
        catch (Exception e) {
            SQLException x = (SQLException) e;
            return x.getClass().getSimpleName() + " " + x.getErrorCode() + " SQLState=" + x.getSQLState() + "：" + x.getMessage();
        }
    }
}
