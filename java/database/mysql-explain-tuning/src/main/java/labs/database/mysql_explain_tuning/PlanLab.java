package labs.database.mysql_explain_tuning;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交易靶场 orders 表（100 万行，和全景 J06 同一份数据，多了 order_no 和 idx_created），练执行计划：
 *   setup    建表、灌数、建索引、ANALYZE
 *   env      版本、慢查询相关变量、统计信息和代价常量
 *   slow     开慢查询日志，跑一段“业务流量”（DAO 写法），从 slow_log 和 digest 表里找出最慢的 3 条
 *   fix      3 条慢 SQL 各看 EXPLAIN / EXPLAIN ANALYZE，改写后再看一遍
 *   bench    十几种 WHERE 写法：索引用没用上、为什么（页面实验台的数据）
 *   cross    回表行数占比从 0.5% 到 50%：优化器在哪儿放弃索引、真实的分界点在哪儿（optimizer_trace 拿代价）
 * 由 run.sh 调用；JDBC 地址默认 127.0.0.1:33072，可用 -Durl=... 覆盖。按 JDK 8 写法（--release 8）。
 */
public class PlanLab {
    static final String URL = System.getProperty("url",
            "jdbc:mysql://127.0.0.1:33072/trade?user=root&password=root&allowPublicKeyRetrieval=true&useSSL=false");

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "bench";
        if ("setup".equals(cmd)) setup();
        else if ("env".equals(cmd)) env();
        else if ("slow".equals(cmd)) slow();
        else if ("fix".equals(cmd)) fix();
        else if ("bench".equals(cmd)) bench();
        else if ("cross".equals(cmd)) cross();
        else throw new IllegalArgumentException(cmd);
    }

    static Connection conn() throws Exception { return DriverManager.getConnection(URL); }

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
            for (int b = 0; b < 10; b++) {           // 100 万行，按主键递增分 10 批插；和 J06 全景、索引页同一份数据
                int o = b * 100000;
                String id = "(" + o + " + i)";
                s.execute("INSERT INTO orders WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100000)"
                        + " SELECT " + id + ", '', 1 + " + id + " * 7919 % 200000, 1 + " + id + " % 500,"
                        + " 1 + " + id + " % 3, 1000 + " + id + " * 37 % 99000,"
                        + " IF(" + id + " % 10 = 0, 'CREATED', 'PAID'),"
                        + " TIMESTAMP('2026-11-01') + INTERVAL " + id + " SECOND, NULL FROM n");
            }
            // 订单号 = 下单日期 + 8 位序号，例如 9527 → 2026110100009527
            s.execute("UPDATE orders SET order_no = CONCAT(DATE_FORMAT(created_at, '%Y%m%d'), LPAD(id, 8, '0'))");
            s.execute("UPDATE orders SET user_id=1024, sku_id=88, qty=2, amount_cents=79800, status='CREATED' WHERE id=9527");
            s.execute("ALTER TABLE orders ADD UNIQUE KEY uk_order_no (order_no), ADD KEY idx_user (user_id), ADD KEY idx_created (created_at)");
            s.execute("ANALYZE TABLE orders");
            System.out.printf("[setup] 100 万行 + 3 个二级索引，用时 %.1f s%n", (System.nanoTime() - t0) / 1e9);
        }
    }

    // ------------------------------------------------------------------ env
    static void env() throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            System.out.println("[env] " + one(s, "SELECT VERSION()"));
            for (String v : new String[]{"slow_query_log", "long_query_time", "log_queries_not_using_indexes",
                    "min_examined_row_limit", "log_slow_extra", "innodb_stats_persistent", "innodb_stats_persistent_sample_pages",
                    "innodb_stats_auto_recalc", "eq_range_index_dive_limit", "explain_format", "explain_json_format_version",
                    "innodb_buffer_pool_size"}) {
                try (ResultSet r = s.executeQuery("SHOW VARIABLES LIKE '" + v + "'")) {
                    System.out.printf("  %-38s %s%n", v, r.next() ? r.getString(2) : "(没有这个变量)");
                }
            }
            System.out.println("  代价常量（mysql.server_cost / engine_cost 的默认值）：");
            try (ResultSet r = s.executeQuery("SELECT cost_name, default_value FROM mysql.server_cost"
                    + " UNION ALL SELECT cost_name, default_value FROM mysql.engine_cost")) {
                while (r.next()) System.out.printf("    %-30s %s%n", r.getString(1), r.getString(2));
            }
            try (ResultSet r = s.executeQuery("SELECT n_rows, clustered_index_size, sum_of_other_index_sizes"
                    + " FROM mysql.innodb_table_stats WHERE database_name='trade' AND table_name='orders'")) {
                r.next();
                System.out.printf("  统计信息：n_rows=%s（真实 1000000）主键 %s 页，其他索引共 %s 页%n",
                        r.getString(1), r.getString(2), r.getString(3));
            }
        }
    }

    // ------------------------------------------------------------------ slow
    static void slow() throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("SET GLOBAL slow_query_log = ON");
            s.execute("SET GLOBAL log_output = 'FILE,TABLE'");     // 表里也写一份，JDBC 直接查
            s.execute("SET GLOBAL long_query_time = 0.1");          // 默认 10 秒，生产常设 0.5～1 秒
            s.execute("SET GLOBAL log_slow_extra = ON");
            s.execute("TRUNCATE TABLE mysql.slow_log");
            s.execute("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest");
        }
        // 新连接才拿到新的 long_query_time（会话值在连接时从全局复制）
        long t0 = System.nanoTime();
        try (Connection c = conn()) {
            PreparedStatement byId = c.prepareStatement("SELECT * FROM orders WHERE id = ?");
            PreparedStatement byUser = c.prepareStatement("SELECT * FROM orders WHERE user_id = ? ORDER BY created_at DESC");
            PreparedStatement byNo = c.prepareStatement("SELECT * FROM orders WHERE order_no = ?");
            PreparedStatement daily = c.prepareStatement("SELECT COUNT(*), SUM(amount_cents) FROM orders WHERE DATE(created_at) = ?");
            PreparedStatement page = c.prepareStatement("SELECT * FROM orders ORDER BY created_at DESC LIMIT ?, 20");
            for (int i = 0; i < 300; i++) {
                byId.setLong(1, 1 + i * 3331L); drain(byId);
                byUser.setLong(1, 1 + i * 661L); drain(byUser);
                if (i % 30 == 0) {                                       // 10 次：订单号字段在 Java 里是 Long
                    byNo.setLong(1, Long.parseLong(orderNo(9527 + i * 1000))); drain(byNo);
                }
                if (i % 100 == 0) {                                      // 3 次：后台对账，按天汇总
                    daily.setString(1, "2026-11-0" + (3 + i / 100)); drain(daily);
                }
                if (i % 100 == 50) {                                     // 3 次：后台订单列表翻到很后面
                    page.setInt(1, 900000); drain(page);
                }
            }
        }
        System.out.printf("[slow] 业务流量跑完：%.1f s（300 次按 id、300 次按用户、10 次按订单号、3 次对账、3 次深分页）%n",
                (System.nanoTime() - t0) / 1e9);
        try (Connection c = conn(); Statement s = c.createStatement()) {
            System.out.println("  慢查询日志（mysql.slow_log，long_query_time=0.1）：");
            try (ResultSet r = s.executeQuery("SELECT COUNT(*), LEFT(CONVERT(sql_text USING utf8mb4), 46) q,"
                    + " AVG(TIME_TO_SEC(query_time) + MICROSECOND(query_time) / 1e6), AVG(rows_examined), AVG(rows_sent)"
                    + " FROM mysql.slow_log GROUP BY q ORDER BY 3 DESC")) {
                while (r.next()) System.out.printf("    %2d 条  平均 %.3f s  扫 %8.0f 行  回 %4.0f 行  %s%n",
                        r.getInt(1), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getString(2));
            }
            System.out.println("  performance_schema 按指纹汇总（总耗时前 5）：");
            try (ResultSet r = s.executeQuery("SELECT COUNT_STAR, ROUND(SUM_TIMER_WAIT/1e12, 2), ROUND(AVG_TIMER_WAIT/1e9, 2),"
                    + " ROUND(SUM_ROWS_EXAMINED/COUNT_STAR), SUM_NO_INDEX_USED, LEFT(DIGEST_TEXT, 72)"
                    + " FROM performance_schema.events_statements_summary_by_digest"
                    + " WHERE SCHEMA_NAME='trade' ORDER BY SUM_TIMER_WAIT DESC LIMIT 5")) {
                while (r.next()) System.out.printf("    %3d 次  共 %6.2f s  平均 %8.2f ms  每次扫 %8d 行  无索引 %3d 次  %s%n",
                        r.getInt(1), r.getDouble(2), r.getDouble(3), r.getLong(4), r.getInt(5), r.getString(6));
            }
            s.execute("SET GLOBAL slow_query_log = OFF");
            s.execute("SET GLOBAL long_query_time = 10");
        }
    }

    static String orderNo(long id) {
        // 和 setup 里的规则一致：created_at = 2026-11-01 + id 秒
        java.time.LocalDateTime t = java.time.LocalDateTime.of(2026, 11, 1, 0, 0).plusSeconds(id);
        return String.format("%04d%02d%02d%08d", t.getYear(), t.getMonthValue(), t.getDayOfMonth(), id);
    }

    // ------------------------------------------------------------------ fix
    static void fix() throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            String no = orderNo(9527);
            System.out.println("[fix] ① 对账：列上套了函数");
            show(s, "改前", "SELECT COUNT(*), SUM(amount_cents) FROM orders WHERE DATE(created_at) = '2026-11-05'");
            show(s, "改后", "SELECT COUNT(*), SUM(amount_cents) FROM orders WHERE created_at >= '2026-11-05' AND created_at < '2026-11-06'");
            System.out.println("[fix] ② 按订单号查：VARCHAR 列拿数字比");
            show(s, "改前", "SELECT * FROM orders WHERE order_no = " + no);
            show(s, "改后", "SELECT * FROM orders WHERE order_no = '" + no + "'");
            System.out.println("[fix] ③ 后台列表翻到第 45001 页");
            show(s, "改前", "SELECT * FROM orders ORDER BY created_at DESC LIMIT 900000, 20");
            String last;
            try (ResultSet r = s.executeQuery("SELECT created_at FROM orders ORDER BY created_at DESC LIMIT 899999, 1")) {
                r.next(); last = r.getString(1);
            }
            show(s, "延迟关联", "SELECT o.* FROM orders o JOIN (SELECT id FROM orders ORDER BY created_at DESC LIMIT 900000, 20) t ON o.id = t.id");
            show(s, "游标分页", "SELECT * FROM orders WHERE created_at < '" + last + "' ORDER BY created_at DESC LIMIT 20");
        }
    }

    /** EXPLAIN 一行 + 警告 + EXPLAIN ANALYZE 的前两行 + 实测耗时（预热后 3 次取最快）。 */
    static void show(Statement s, String label, String sql) throws Exception {
        long ns = best(s, sql, 3);
        System.out.printf("  %-6s %8.2f ms  %s%n", label, ns / 1e6, sql);
        System.out.println("         EXPLAIN  " + explainAll(s, sql));
        try (ResultSet r = s.executeQuery("EXPLAIN ANALYZE " + sql)) {
            r.next();
            String[] lines = r.getString(1).split("\n");
            for (int i = 0; i < Math.min(3, lines.length); i++) System.out.println("         " + lines[i].trim());
        }
    }

    static long best(Statement s, String sql, int n) throws Exception {
        drain(s, sql);
        long best = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            drain(s, sql);
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best;
    }

    static String explainAll(Statement s, String sql) throws Exception {
        StringBuilder b = new StringBuilder();
        try (ResultSet r = s.executeQuery("EXPLAIN " + sql)) {
            while (r.next()) {
                if (b.length() > 0) b.append(" | ");
                b.append("table=").append(r.getString("table")).append(" type=").append(r.getString("type"))
                        .append(" key=").append(r.getString("key")).append(" rows=").append(r.getString("rows"))
                        .append(" filtered=").append(r.getString("filtered"));
                if (r.getString("Extra") != null) b.append(" Extra=").append(r.getString("Extra"));
            }
            for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                if (w.getErrorCode() != 1003) b.append("\n         警告 ").append(w.getErrorCode()).append(": ").append(w.getMessage());
            }
            s.clearWarnings();
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ bench
    static void bench() throws Exception {
        String no = orderNo(9527);
        String[][] cases = {
                {"A", "WHERE user_id = 1024"},
                {"B", "WHERE user_id + 0 = 1024"},
                {"C", "WHERE user_id = '1024'"},
                {"D", "WHERE order_no = " + no},
                {"E", "WHERE order_no = '" + no + "'"},
                {"F", "WHERE DATE(created_at) = '2026-11-05'"},
                {"G", "WHERE created_at >= '2026-11-05' AND created_at < '2026-11-06'"},
                {"H", "WHERE order_no LIKE '%09527'"},
                {"I", "WHERE order_no LIKE '2026110100009%'"},
                {"J", "WHERE user_id = 1024 OR sku_id = 88"},
                {"K", "WHERE user_id = 1024 OR order_no = '" + no + "'"},
                {"L", "WHERE user_id <> 1024"},
                {"M", "WHERE user_id BETWEEN 1 AND 2000"},
                {"N", "WHERE user_id BETWEEN 1 AND 60000"},
                {"O", "ORDER BY created_at DESC LIMIT 20"},
                {"P", "ORDER BY created_at DESC LIMIT 900000, 20"},
        };
        try (Connection c = conn(); Statement s = c.createStatement()) {
            for (String[] k : cases) {
                String sql = "SELECT * FROM orders " + k[1];
                long ns = best(s, sql, 3);
                long rows = count(s, "SELECT COUNT(*) FROM (" + sql + ") t");
                System.out.printf("[bench] %s %8.2f ms  返回 %6d 行  %-58s %s%n", k[0], ns / 1e6, rows, k[1],
                        explainAll(s, sql).replace("\n", " "));
            }
        }
    }

    // ------------------------------------------------------------------ cross
    static final Pattern TABLE_SCAN = Pattern.compile("\"table_scan\": \\{\\s*\"rows\": (\\d+),\\s*\"cost\": ([\\d.e+]+)");
    static final Pattern RANGE = Pattern.compile("\"index\": \"(idx_\\w+)\",\\s*\"ranges\": \\[[^\\]]*\\],[^}]*?\"rows\": (\\d+),\\s*\"cost\": ([\\d.e+]+)");

    static void cross() throws Exception {
        double[] fr = {0.005, 0.01, 0.02, 0.05, 0.10, 0.15, 0.20, 0.30, 0.50};
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("SET optimizer_trace = 'enabled=on'");
            s.execute("SET optimizer_trace_max_mem_size = 1048576");
            System.out.println("[cross] user_id：和主键顺序无关，回表是随机跳页");
            for (double f : fr) {
                long hi = Math.round(200000 * f);
                row(s, f, "WHERE user_id BETWEEN 1 AND " + hi, "idx_user");
            }
            System.out.println("[cross] created_at：和主键同序，回表是顺着往后读");
            for (double f : fr) {
                long sec = Math.round(1000000 * f);
                row(s, f, "WHERE created_at >= '2026-11-01' AND created_at < TIMESTAMP('2026-11-01') + INTERVAL " + sec + " SECOND", "idx_created");
            }
        }
    }

    static void row(Statement s, double f, String where, String idx) throws Exception {
        String sql = "SELECT SUM(amount_cents) FROM orders " + where;
        drain(s, "EXPLAIN " + sql);
        String trace = one(s, "SELECT TRACE FROM information_schema.OPTIMIZER_TRACE");
        String scanCost = "?", rangeRows = "?", rangeCost = "?";
        Matcher m = TABLE_SCAN.matcher(trace);
        if (m.find()) scanCost = m.group(2);
        m = RANGE.matcher(trace);
        while (m.find()) if (m.group(1).equals(idx)) { rangeRows = m.group(2); rangeCost = m.group(3); }
        String chosen;
        try (ResultSet r = s.executeQuery("EXPLAIN " + sql)) { r.next(); chosen = r.getString("key"); }
        long useIdx = best(s, "SELECT SUM(amount_cents) FROM orders FORCE INDEX (" + idx + ") " + where, 3);
        long scan = best(s, "SELECT SUM(amount_cents) FROM orders IGNORE INDEX (" + idx + ") " + where, 3);
        System.out.printf("  %5.1f%%  估 %7s 行  代价 走索引 %10s / 全表扫 %10s  → 选 %-11s  实测 走索引 %8.2f ms / 全表扫 %8.2f ms%n",
                f * 100, rangeRows, rangeCost, scanCost, chosen == null ? "全表扫" : chosen, useIdx / 1e6, scan / 1e6);
    }

    // ------------------------------------------------------------------ util
    static void drain(PreparedStatement p) throws Exception {
        try (ResultSet r = p.executeQuery()) { while (r.next()) { /* 读完 */ } }
    }

    static void drain(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery(sql)) { while (r.next()) { /* 读完 */ } }
    }

    static long count(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    static String one(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }
}
