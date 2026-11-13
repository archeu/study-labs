package labs.database.mysql_redo_undo_binlog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交易靶场 orders 表（100 万行，和全景 J06 同一份数据），看一条付款 UPDATE 在三本账上各留下什么：
 *   setup   建表、灌 100 万行
 *   see     ⑤ 亲眼看到：UPDATE 9527 后 redo / undo / binlog 各动了没有；ROLLBACK 一次、COMMIT 一次
 *   purge   undo 不能马上删：一个开着的快照让 History list length 涨上去，旧快照读 9527 要沿版本链走多远
 *   redo    redo 容量 8 MB 和 1 GB，跑同样的批量更新：检查点被逼着往前推、脏页被逼着刷
 * 崩溃恢复（提交途中 kill -9）要杀容器，放在 crash.sh 里。
 * 由 run.sh 调用；JDBC 地址默认 127.0.0.1:33074，可用 -Durl=... 覆盖。按 JDK 8 写法（--release 8）。
 */
public class LogLab {
    static final String URL = System.getProperty("url",
            "jdbc:mysql://127.0.0.1:33074/trade?user=root&password=root&allowPublicKeyRetrieval=true&useSSL=false");

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "see";
        if ("setup".equals(cmd)) setup();
        else if ("see".equals(cmd)) see();
        else if ("purge".equals(cmd)) purge();
        else if ("redo".equals(cmd)) redo();
        else throw new IllegalArgumentException(cmd);
    }

    // ------------------------------------------------------------------ 小工具
    static Connection conn() throws SQLException { return DriverManager.getConnection(URL); }
    static String one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.next() ? r.getString(1) : null; }
    }
    static long num(Connection c, String sql) throws SQLException { return Long.parseLong(one(c, sql)); }
    static int upd(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { return s.executeUpdate(sql); }
    }
    static long status(Connection c, String var) throws SQLException {
        return num(c, "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='" + var + "'");
    }
    static String innodbStatus(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW ENGINE INNODB STATUS")) { r.next(); return r.getString(3); }
    }
    static long grab(String text, String label) {
        Matcher m = Pattern.compile(label + "\\s+(\\d+)").matcher(text);
        if (!m.find()) throw new IllegalStateException("没找到 " + label);
        return Long.parseLong(m.group(1));
    }
    /** redo 写到哪了（LSN）、检查点在哪、还有多少 undo 等着 purge */
    static long[] logState(Connection c) throws SQLException {
        String t = innodbStatus(c);
        return new long[]{grab(t, "Log sequence number"), grab(t, "Last checkpoint at"), grab(t, "History list length")};
    }
    /** binlog 当前文件和位置（8.4 里 SHOW MASTER STATUS 已经删了） */
    static String[] binlogPos(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW BINARY LOG STATUS")) {
            r.next(); return new String[]{r.getString(1), r.getString(2)};
        }
    }
    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }

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
            for (int b = 0; b < 10; b++) {
                int o = b * 100000;
                String id = "(" + o + " + i)";
                s.execute("INSERT INTO orders WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100000)"
                        + " SELECT " + id + ", CONCAT('20261101', LPAD(" + id + ", 8, '0')), 1 + " + id + " * 7919 % 200000, 1 + " + id + " % 500,"
                        + " 1 + " + id + " % 3, 1000 + " + id + " * 37 % 99000,"
                        + " IF(" + id + " % 10 = 0, 'CREATED', 'PAID'),"
                        + " TIMESTAMP('2026-11-01') + INTERVAL " + id + " SECOND, NULL FROM n");
            }
            s.execute("UPDATE orders SET user_id=1024, sku_id=88, qty=2, amount_cents=79800, status='CREATED' WHERE id=9527");
            s.execute("ALTER TABLE orders ADD KEY idx_user (user_id)");
            System.out.printf("[setup] orders 100 万行，用时 %.1f s%n", (System.nanoTime() - t0) / 1e9);
        }
    }

    // ------------------------------------------------------------------ see：⑤ 亲眼看到
    static final String PAY = "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'";

    static void see() throws Exception {
        try (Connection a = conn(); Connection obs = conn(); Connection other = conn()) {
            upd(obs, "UPDATE orders SET status='CREATED', paid_at=NULL WHERE id=9527");
            Thread.sleep(1500);                        // 等后台把刚才的 redo 刷完，基线干净一点
            for (String end : new String[]{"ROLLBACK", "COMMIT"}) {
                System.out.println("[see] 付款 UPDATE，最后 " + end);
                long[] l0 = logState(obs);
                String[] b0 = binlogPos(obs);
                a.setAutoCommit(false);
                int rows = upd(a, PAY);
                long[] l1 = logState(obs);
                String[] b1 = binlogPos(obs);
                String undoRows = one(obs, "SELECT trx_rows_modified FROM information_schema.innodb_trx");
                String seen = one(other, "SELECT status FROM orders WHERE id=9527");
                System.out.printf("  UPDATE 改了 %d 行 | redo LSN +%d 字节 | binlog %s:%s → %s:%s | 未提交事务改了 %s 行（旧值在 undo）| 另一个连接读到 %s%n",
                        rows, l1[0] - l0[0], b0[0], b0[1], b1[0], b1[1], undoRows, seen);
                long t0 = System.nanoTime();
                if ("COMMIT".equals(end)) a.commit(); else a.rollback();
                double t = ms(t0);
                a.setAutoCommit(true);
                long[] l2 = logState(obs);
                String[] b2 = binlogPos(obs);
                System.out.printf("  %s 用时 %.2f ms | redo LSN 再 +%d 字节 | binlog %s:%s → %s:%s（+%d 字节）| 现在 9527 = %s%n",
                        end, t, l2[0] - l1[0], b1[0], b1[1], b2[0], b2[1],
                        Long.parseLong(b2[1]) - Long.parseLong(b1[1]),
                        one(obs, "SELECT status FROM orders WHERE id=9527"));
                if ("COMMIT".equals(end)) {
                    try (Statement s = obs.createStatement();
                         ResultSet r = s.executeQuery("SHOW BINLOG EVENTS IN '" + b1[0] + "' FROM " + b1[1])) {
                        while (r.next()) System.out.printf("    binlog 事件 %-12s %4d 字节  %s%n", r.getString("Event_type"),
                                r.getLong("End_log_pos") - r.getLong("Pos"), r.getString("Info"));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ purge：undo 什么时候才能删
    static void purge() throws Exception {
        int n = Integer.getInteger("n", 20000);
        try (Connection a = conn(); Connection report = conn(); Connection obs = conn()) {
            upd(a, "UPDATE orders SET status='CREATED', paid_at=NULL WHERE id=9527");
            waitHll(obs, 50, 30000);
            long[] s0 = logState(obs);
            report.setAutoCommit(false);
            upd(report, "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            one(report, "SELECT status FROM orders WHERE id=9527");     // 报表事务第一次读：拍下快照
            long t = System.nanoTime();
            for (int i = 0; i < n; i++)                                    // 9527 被反复改（每次自动提交）
                upd(a, "UPDATE orders SET qty = qty + 1 WHERE id=9527");
            double writeMs = ms(t);
            Thread.sleep(3000);                                            // 给 purge 线程留时间：它也删不掉
            long[] s1 = logState(obs);
            System.out.printf("[purge] 报表事务开着，9527 被改了 %d 次（%.0f ms）；3 秒后 History list length %d → %d%n",
                    n, writeMs, s0[2], s1[2]);
            for (int k = 0; k < 3; k++) {
                long t1 = System.nanoTime();
                String old = one(report, "SELECT qty FROM orders WHERE id=9527");
                double tOld = ms(t1);
                long t2 = System.nanoTime();
                String cur = one(obs, "SELECT qty FROM orders WHERE id=9527");
                double tNew = ms(t2);
                System.out.printf("  报表事务读 9527：qty=%s，%.2f ms（沿 undo 版本链往回走 %d 步） | 新连接读：qty=%s，%.2f ms%n",
                        old, tOld, n, cur, tNew);
            }
            long t3 = System.nanoTime();
            report.commit();
            long h = waitHll(obs, 50, 60000);
            System.out.printf("  报表事务提交后 %.1f s，History list length 降到 %d（purge 把这些旧版本清掉了）%n", ms(t3) / 1000, h);
            upd(a, "UPDATE orders SET qty=2 WHERE id=9527");
        }
    }
    static long waitHll(Connection c, long below, long maxMs) throws Exception {
        long end = System.currentTimeMillis() + maxMs, h;
        while ((h = logState(c)[2]) > below && System.currentTimeMillis() < end) Thread.sleep(200);
        return h;
    }

    // ------------------------------------------------------------------ redo：容量太小会怎样
    static void redo() throws Exception {
        int rows = Integer.getInteger("rows", 300000), chunk = 5000;
        try (Connection c = conn(); Connection obs = conn()) {
            for (long cap : new long[]{8L << 20, 1L << 30}) {
                upd(c, "SET GLOBAL innodb_redo_log_capacity = " + cap);
                // 先把脏页刷干净，两轮从同一个起点出发
                upd(c, "SET GLOBAL innodb_max_dirty_pages_pct = 0");
                long end = System.currentTimeMillis() + 120000;
                while (status(obs, "Innodb_buffer_pool_pages_dirty") > 50 && System.currentTimeMillis() < end) Thread.sleep(500);
                upd(c, "SET GLOBAL innodb_max_dirty_pages_pct = 90");
                while (!"OK".equals(one(obs, "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_redo_log_resize_status'"))) Thread.sleep(200);
                Thread.sleep(2000);

                final AtomicLong maxAge = new AtomicLong();
                final boolean[] stop = {false};
                Thread sampler = new Thread(() -> {
                    try (Connection s = conn()) {
                        while (!stop[0]) {
                            long[] l = logState(s);
                            maxAge.accumulateAndGet(l[0] - l[1], Math::max);
                            Thread.sleep(50);
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long flushed0 = status(obs, "Innodb_buffer_pool_pages_flushed");
                long lsn0 = logState(obs)[0];
                sampler.start();
                double worst = 0;
                long t0 = System.nanoTime();
                for (int from = 1; from <= rows; from += chunk) {            // 自动提交，每批 5000 行；只改定长的 qty，原地更新，两轮写的 redo 一样多
                    long t = System.nanoTime();
                    upd(c, "UPDATE orders SET qty = qty + 1 WHERE id BETWEEN " + from + " AND " + (from + chunk - 1));
                    worst = Math.max(worst, ms(t));
                }
                double total = ms(t0);
                stop[0] = true;
                sampler.join();
                long redoBytes = logState(obs)[0] - lsn0;
                long flushed = status(obs, "Innodb_buffer_pool_pages_flushed") - flushed0;
                System.out.printf("[redo] 容量 %4d MB：改 %d 行用时 %.1f s，最慢一批 %.0f ms | 写了 redo %.1f MB | 检查点最多落后 %.1f MB | 期间刷了 %d 个页（%.0f MB）%n",
                        cap >> 20, rows, total / 1000, worst, redoBytes / 1048576.0, maxAge.get() / 1048576.0,
                        flushed, flushed * 16 / 1024.0);
            }
            upd(c, "SET GLOBAL innodb_redo_log_capacity = " + (100L << 20));
        }
    }
}
