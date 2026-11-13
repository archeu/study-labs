package labs.java.pano_j06_update_to_disk;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;

/**
 * 全景 J06：一条 UPDATE 从应用到磁盘。
 * 订单服务给订单 9527 付款：UPDATE orders SET status='PAID' ... WHERE id=9527 AND status='CREATED'。
 * 应用连主库（127.0.0.1:33061），另开一条 root 连接在旁边“看”：执行计划、读盘、行锁、undo、redo LSN、binlog 位点、脏页；
 * 最后去从库（33062）等这条改动出现。由 run.sh 起好两个容器后调用：setup / trace / bench。
 */
public class UpdateToDisk {
    static final String PRIMARY = "jdbc:mysql://127.0.0.1:33061/";
    static final String REPLICA = "jdbc:mysql://127.0.0.1:33062/";
    static final String OPTS = "?allowPublicKeyRetrieval=true&useSSL=false";
    static final String UPDATE_SQL =
            "UPDATE orders SET status = ?, paid_at = NOW(6) WHERE id = ? AND status = ?";

    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "trace" : args[0]) {
            case "setup" -> setup();
            case "trace" -> trace();
            case "bench" -> bench();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static Connection root(String url, String db) throws SQLException {
        return DriverManager.getConnection(url + db + OPTS, "root", "root");
    }

    // ---------------------------------------------------------------- setup
    static void setup() throws Exception {
        try (Connection c = root(PRIMARY, ""); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS trade");
            s.execute("USE trade");
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("""
                CREATE TABLE orders (
                  id           BIGINT      NOT NULL PRIMARY KEY,
                  user_id      BIGINT      NOT NULL,
                  sku_id       BIGINT      NOT NULL,
                  qty          INT         NOT NULL,
                  amount_cents BIGINT      NOT NULL,
                  status       VARCHAR(16) NOT NULL,
                  created_at   DATETIME(6) NOT NULL,
                  paid_at      DATETIME(6) NULL,
                  KEY idx_user (user_id)
                ) ENGINE=InnoDB""");
            // 另一张小表：追踪前先在它上面走一遍同样的 JDBC 调用，让驱动的类加载和 JIT 不算进第一单
            s.execute("DROP TABLE IF EXISTS warmup");
            s.execute("CREATE TABLE warmup (id BIGINT PRIMARY KEY, status VARCHAR(16), paid_at DATETIME(6))");
            s.execute("INSERT INTO warmup VALUES (1, 'CREATED', NULL)");
            s.execute("SET SESSION cte_max_recursion_depth = 1000000");
            long t0 = System.nanoTime();
            // 100 万行，分 10 批插，避免一个巨型事务把从库卡住
            for (int b = 0; b < 10; b++) {
                s.execute("""
                    INSERT INTO orders
                    WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100000)
                    SELECT %d + i, 1 + (%d + i) * 7919 %% 200000, 1 + (%d + i) %% 500, 1 + (%d + i) %% 3,
                           1000 + (%d + i) * 37 %% 99000,
                           IF((%d + i) %% 10 = 0, 'CREATED', 'PAID'),
                           TIMESTAMP('2026-11-01') + INTERVAL (%d + i) SECOND, NULL
                    FROM n""".formatted(b * 100000, b * 100000, b * 100000, b * 100000, b * 100000, b * 100000, b * 100000));
            }
            // 场景里的那一单：用户 1024 买了 2 件 88 号商品，798.00 元，待付款
            s.execute("UPDATE orders SET user_id = 1024, sku_id = 88, qty = 2, amount_cents = 79800, status = 'CREATED' WHERE id = 9527");
            s.execute("ANALYZE TABLE orders");
            System.out.printf("[setup] orders 灌入 100 万行，用时 %.1f s%n", (System.nanoTime() - t0) / 1e9);
            try (ResultSet r = s.executeQuery("""
                    SELECT ROUND(data_length/1024/1024), ROUND(index_length/1024/1024)
                    FROM information_schema.tables WHERE table_schema='trade' AND table_name='orders'""")) {
                r.next();
                System.out.printf("[setup] 聚簇索引（数据）约 %d MB，二级索引 idx_user 约 %d MB%n", r.getLong(1), r.getLong(2));
            }
        }
        // 等从库追上
        long t0 = System.nanoTime();
        try (Connection rc = root(REPLICA, ""); Statement s = rc.createStatement()) {
            while (true) {
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM trade.orders")) {
                    if (r.next() && r.getLong(1) == 1_000_000) break;
                } catch (SQLException e) { /* 库或表还没复制过来 */ }
                Thread.sleep(500);
            }
        }
        System.out.printf("[setup] 从库追上，等了 %.1f s%n", (System.nanoTime() - t0) / 1e9);
    }

    // ---------------------------------------------------------------- trace
    static void trace() throws Exception {
        try (Connection obs = root(PRIMARY, "trade"); Statement o = obs.createStatement()) {
            // 打开语句阶段的计时（默认只开了语句级），这样能看到解析、打开表、执行、提交各花多久
            o.execute("UPDATE performance_schema.setup_instruments SET ENABLED='YES', TIMED='YES' WHERE NAME LIKE 'stage/%'");
            o.execute("UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME LIKE 'events_stages%'");

            waitReplica(o, "主库重启后");
            System.out.println("== 第 4 站：优化器怎么找这一行（EXPLAIN）==");
            printRows(o, "EXPLAIN UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'",
                    "select_type", "table", "type", "possible_keys", "key", "key_len", "rows", "Extra");
            printRows(o, "EXPLAIN UPDATE orders SET status='PAID' WHERE user_id=1024 AND status='CREATED'",
                    "type", "key", "rows");
            printRows(o, "EXPLAIN UPDATE orders SET status='PAID' WHERE sku_id=88 AND status='CREATED'",
                    "type", "key", "rows");

            System.out.println("== 第 5 站之前：buffer pool 里有没有 orders 的页 ==");
            int before = cachedPages(o);
            System.out.printf("重启后 orders 主键索引在 buffer pool 里的页：%d 个%n", before);

            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(PRIMARY + "trade" + OPTS);
            hc.setUsername("root");
            hc.setPassword("root");
            hc.setMaximumPoolSize(2);
            hc.setPoolName("order-pool");
            try (HikariDataSource ds = new HikariDataSource(hc)) {
                // 先预热一次连接（建 TCP、认证、握手），场景里连接池早就建好了
                try (Connection c = ds.getConnection()) { c.isValid(1); }
                for (int i = 0; i < 20; i++) {
                    try (Connection c = ds.getConnection()) {
                        c.setAutoCommit(false);
                        try (PreparedStatement ps = c.prepareStatement(UPDATE_SQL.replace("orders", "warmup"))) {
                            ps.setString(1, i % 2 == 0 ? "PAID" : "CREATED"); ps.setLong(2, 1); ps.setString(3, i % 2 == 0 ? "CREATED" : "PAID");
                            ps.executeUpdate();
                        }
                        c.commit();
                    }
                }

                long t = System.nanoTime();
                Connection c = ds.getConnection();
                long borrowUs = (System.nanoTime() - t) / 1000;
                long appThread = threadIdOf(c);
                t = System.nanoTime();
                c.setAutoCommit(false);
                long beginUs = (System.nanoTime() - t) / 1000;
                System.out.printf("== 第 2 站：HikariCP 借连接 %d µs，setAutoCommit(false) %d µs ==%n", borrowUs, beginUs);

                Map<String, Long> s0 = status(o);
                String bl0 = binlogPos(o);
                t = System.nanoTime();
                int n;
                try (PreparedStatement ps = c.prepareStatement(UPDATE_SQL)) {
                    ps.setString(1, "PAID");
                    ps.setLong(2, 9527);
                    ps.setString(3, "CREATED");
                    n = ps.executeUpdate();
                }
                long updUs = (System.nanoTime() - t) / 1000;
                Map<String, Long> s1 = status(o);
                String bl1 = binlogPos(o);
                System.out.printf("== 第 3～8 站：executeUpdate 返回 %d 行，客户端计时 %d µs（buffer pool 是冷的）==%n", n, updUs);
                System.out.println("服务器收到的语句（Connector/J 默认在客户端拼好参数，发的是整条文本）：");
                printRows(o, "SELECT SQL_TEXT FROM performance_schema.events_statements_history WHERE THREAD_ID=" + appThread
                        + " AND SQL_TEXT LIKE 'UPDATE orders%' ORDER BY EVENT_ID DESC LIMIT 1", "SQL_TEXT");
                System.out.println("这条语句在服务器里的阶段（µs）：");
                printStages(o, appThread, "UPDATE orders%");
                System.out.printf("服务器端整条语句 %d µs（客户端多出来的是网络往返和驱动）%n", stmtUs(o, appThread, "UPDATE orders%"));
                System.out.printf("读盘：Innodb_buffer_pool_reads +%d（从磁盘读进来的页），Innodb_buffer_pool_read_requests +%d%n",
                        d(s0, s1, "Innodb_buffer_pool_reads"), d(s0, s1, "Innodb_buffer_pool_read_requests"));
                System.out.printf("现在 orders 主键索引在 buffer pool 里的页：%d 个；其中脏页：%d 个%n", cachedPages(o), dirtyPages(o));
                printRows(o, """
                        SELECT PAGE_NUMBER, NUMBER_RECORDS, IF(OLDEST_MODIFICATION>0,'脏','干净') AS state
                        FROM information_schema.INNODB_BUFFER_PAGE
                        WHERE TABLE_NAME='`trade`.`orders`' AND INDEX_NAME='PRIMARY' ORDER BY NUMBER_RECORDS DESC""",
                        "PAGE_NUMBER", "NUMBER_RECORDS", "state");

                System.out.println("== 第 6 站：还没提交，这个事务持有的锁 ==");
                printRows(o, "SELECT OBJECT_NAME, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA FROM performance_schema.data_locks",
                        "OBJECT_NAME", "INDEX_NAME", "LOCK_TYPE", "LOCK_MODE", "LOCK_STATUS", "LOCK_DATA");
                try (Connection other = root(PRIMARY, "trade"); Statement x = other.createStatement()) {
                    x.execute("SET SESSION innodb_lock_wait_timeout = 1");
                    long tw = System.nanoTime();
                    try {
                        x.executeUpdate("UPDATE orders SET status='CANCELLED' WHERE id=9527");
                        System.out.println("另一个事务改同一行：居然成功了");
                    } catch (SQLException e) {
                        System.out.printf("另一个事务想改 9527：等了 %d ms，报 %d %s%n",
                                (System.nanoTime() - tw) / 1_000_000, e.getErrorCode(), e.getMessage());
                    }
                    System.out.println("== 第 7 站：undo 和 MVCC ==");
                    try (ResultSet r = x.executeQuery("SELECT status FROM orders WHERE id=9527")) {
                        r.next();
                        System.out.println("另一个连接此刻读 9527（普通 SELECT，不加锁）：status=" + r.getString(1) + "（从 undo 拼出的旧版本）");
                    }
                }
                printRows(o, "SELECT trx_id, trx_state, trx_rows_modified, trx_rows_locked, trx_isolation_level FROM information_schema.INNODB_TRX",
                        "trx_id", "trx_state", "trx_rows_modified", "trx_rows_locked", "trx_isolation_level");

                System.out.println("== 第 8 站：redo 与 binlog，提交前 ==");
                System.out.printf("redo LSN：UPDATE 前 %d → UPDATE 后 %d（+%d 字节）；此刻已写到 %d、已 fsync 到 %d%n",
                        s0.get("lsn"), s1.get("lsn"), d(s0, s1, "lsn"), s1.get("written"), s1.get("flushed"));
                System.out.printf("  对照：状态变量 Innodb_redo_log_current_lsn 前 %d → 后 %d%n",
                        s0.get("Innodb_redo_log_current_lsn"), s1.get("Innodb_redo_log_current_lsn"));
                System.out.printf("binlog 位点：UPDATE 前 %s → UPDATE 后 %s（还在事务自己的 binlog cache 里，没写进文件）%n", bl0, bl1);

                Map<String, Long> s2 = status(o);
                t = System.nanoTime();
                c.commit();
                long commitUs = (System.nanoTime() - t) / 1000;
                long commitWallMs = System.currentTimeMillis();
                Map<String, Long> s3 = status(o);
                String bl3 = binlogPos(o);
                System.out.printf("== 第 9 站：commit() 客户端计时 %d µs ==%n", commitUs);
                printStages(o, appThread, "COMMIT");
                System.out.printf("服务器端 COMMIT 语句 %d µs%n", stmtUs(o, appThread, "COMMIT"));
                System.out.printf("redo LSN：提交前 %d（已 fsync 到 %d）→ 提交后 %d（已 fsync 到 %d），提交又写了 %d 字节；检查点在 %d%n",
                        s2.get("lsn"), s2.get("flushed"), s3.get("lsn"), s3.get("flushed"), d(s2, s3, "lsn"), s3.get("checkpoint"));
                System.out.printf("提交期间 Innodb_os_log_fsyncs +%d，Innodb_data_fsyncs +%d%n",
                        d(s2, s3, "Innodb_os_log_fsyncs"), d(s2, s3, "Innodb_data_fsyncs"));
                System.out.printf("binlog 位点：%s → %s，这个事务在 binlog 里的事件：%n", bl1, bl3);
                String gtid = null;
                try (ResultSet r = o.executeQuery("SHOW BINLOG EVENTS IN '" + bl1.split(":")[0] + "' FROM " + bl1.split(":")[1])) {
                    while (r.next()) {
                        String info = r.getString("Info");
                        if (gtid == null && info.contains("GTID_NEXT")) gtid = info.replaceAll(".*'(.+)'.*", "$1");
                        System.out.printf("  %-12s %4d 字节  %s%n", r.getString("Event_type"),
                                r.getLong("End_log_pos") - r.getLong("Pos"), info);
                    }
                }

                t = System.nanoTime();
                c.close();
                long closeUs = (System.nanoTime() - t) / 1000;
                System.out.printf("== 第 10 站：close() 把连接还给池 %d µs ==%n", closeUs);
                System.out.printf("应用这边一共（借连接 + 开事务 + UPDATE + 提交 + 还连接，不算旁边观察的时间）：%.2f ms%n",
                        (borrowUs + beginUs + updUs + commitUs + closeUs) / 1000.0);

                System.out.println("== 第 11 站：后台（从库回放、刷脏页）==");
                try (Connection rc = root(REPLICA, "trade"); Statement rs = rc.createStatement()) {
                    long seenAt = -1;
                    while (System.currentTimeMillis() - commitWallMs < 10_000) {
                        try (ResultSet r = rs.executeQuery("SELECT status FROM orders WHERE id=9527")) {
                            r.next();
                            if ("PAID".equals(r.getString(1))) { seenAt = System.currentTimeMillis(); break; }
                        }
                    }
                    System.out.printf("从库上读到 9527=PAID：主库 commit 返回后 %s%n",
                            seenAt < 0 ? "10 秒内没读到" : (seenAt - commitWallMs) + " ms（应用这边轮询看到的）");
                    printRows(rs, """
                            SELECT LAST_APPLIED_TRANSACTION,
                              TIMESTAMPDIFF(MICROSECOND, LAST_APPLIED_TRANSACTION_ORIGINAL_COMMIT_TIMESTAMP,
                                            LAST_APPLIED_TRANSACTION_END_APPLY_TIMESTAMP) AS lag_us
                            FROM performance_schema.replication_applier_status_by_worker
                            WHERE LAST_APPLIED_TRANSACTION = '%s'""".formatted(gtid),
                            "LAST_APPLIED_TRANSACTION", "lag_us");
                }
                long t0 = System.nanoTime();
                int dirty = dirtyPages(o);
                while (dirty > 0 && System.nanoTime() - t0 < 120_000_000_000L) {
                    Thread.sleep(100);
                    dirty = dirtyPages(o);
                }
                Map<String, Long> s4 = status(o);
                System.out.printf("9527 所在的脏页：提交后约 %.1f s 被后台刷回磁盘（还剩 %d 个脏页）；Pages flushed up to %d，检查点 %d%n",
                        (System.nanoTime() - t0) / 1e9, dirty, s4.get("pagesFlushed"), s4.get("checkpoint"));

                // 同一个页上的第二单：buffer pool 已经热了
                Map<String, Long> w0 = status(o);
                try (Connection c2 = ds.getConnection()) {
                    c2.setAutoCommit(false);
                    long tw = System.nanoTime();
                    try (PreparedStatement ps = c2.prepareStatement(UPDATE_SQL)) {
                        ps.setString(1, "PAID"); ps.setLong(2, 9530); ps.setString(3, "CREATED");
                        ps.executeUpdate();
                    }
                    long warmUs = (System.nanoTime() - tw) / 1000;
                    tw = System.nanoTime();
                    c2.commit();
                    System.out.printf("对照：同一页上的订单 9530，热 buffer pool 下 executeUpdate %d µs，commit %d µs，读盘 +%d%n",
                            warmUs, (System.nanoTime() - tw) / 1000, d(w0, status(o), "Innodb_buffer_pool_reads"));
                }

                printRows(o, "SHOW VARIABLES WHERE Variable_name IN ('innodb_flush_log_at_trx_commit','sync_binlog','binlog_format','transaction_isolation','innodb_buffer_pool_size','innodb_page_size','innodb_redo_log_capacity','version')",
                        "Variable_name", "Value");
            }
            // 还原，方便 ./run.sh again 再跑一次
            o.execute("UPDATE orders SET status='CREATED', paid_at=NULL WHERE id IN (9527, 9530)");
            waitReplica(o, "还原后");
        }
    }

    static void waitReplica(Statement primary, String when) throws Exception {
        String gtids;
        try (ResultSet r = primary.executeQuery("SELECT @@GLOBAL.gtid_executed")) { r.next(); gtids = r.getString(1); }
        long t = System.nanoTime();
        try (Connection rc = root(REPLICA, ""); PreparedStatement ps = rc.prepareStatement("SELECT WAIT_FOR_EXECUTED_GTID_SET(?, 120)")) {
            ps.setString(1, gtids);
            try (ResultSet r = ps.executeQuery()) { r.next(); if (r.getInt(1) != 0) throw new IllegalStateException("从库 120 秒没追上"); }
        }
        System.out.printf("[%s] 从库已追上主库，等了 %.1f s%n", when, (System.nanoTime() - t) / 1e9);
    }

    // ---------------------------------------------------------------- bench
    /** 第 9 站提交时要等两次落盘（redo、binlog）。改两个参数，看每次提交差多少。 */
    static void bench() throws Exception {
        try (Connection o = root(PRIMARY, "trade"); Statement s = o.createStatement();
             Connection c = root(PRIMARY, "trade")) {
            String[][] cfgs = {{"1", "1"}, {"2", "0"}};
            for (String[] cfg : cfgs) {
                s.execute("SET GLOBAL innodb_flush_log_at_trx_commit = " + cfg[0]);
                s.execute("SET GLOBAL sync_binlog = " + cfg[1]);
                long[] lat = new long[300];
                Map<String, Long> a = status(s);
                try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET qty = qty + 1 WHERE id = ?")) {
                    for (int i = 0; i < 50; i++) { ps.setLong(1, 200_000 + i); ps.executeUpdate(); } // 热身
                    a = status(s);
                    for (int i = 0; i < lat.length; i++) {
                        ps.setLong(1, 300_000 + i * 997);
                        long t = System.nanoTime();
                        ps.executeUpdate(); // autocommit：一条 UPDATE 就是一个事务
                        lat[i] = System.nanoTime() - t;
                    }
                }
                Map<String, Long> b = status(s);
                Arrays.sort(lat);
                System.out.printf("[bench] innodb_flush_log_at_trx_commit=%s sync_binlog=%s：300 次自动提交的 UPDATE，p50 %.2f ms，p99 %.2f ms，redo fsync %d 次%n",
                        cfg[0], cfg[1], lat[150] / 1e6, lat[297] / 1e6, d(a, b, "Innodb_os_log_fsyncs"));
            }
            s.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 1");
            s.execute("SET GLOBAL sync_binlog = 1");
        }
    }

    // ---------------------------------------------------------------- helpers
    static long threadIdOf(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT PS_CURRENT_THREAD_ID()")) {
            r.next();
            return r.getLong(1);
        }
    }

    static Map<String, Long> status(Statement s) throws SQLException {
        Map<String, Long> m = new HashMap<>();
        try (ResultSet r = s.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ("
                + "'Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_redo_log_current_lsn',"
                + "'Innodb_redo_log_flushed_to_disk_lsn','Innodb_os_log_fsyncs','Innodb_data_fsyncs','Innodb_buffer_pool_pages_dirty')")) {
            while (r.next()) m.put(r.getString(1), r.getLong(2));
        }
        // LSN 从 SHOW ENGINE INNODB STATUS 的 LOG 段取（Innodb_redo_log_current_lsn 这个状态变量实测会滞后）
        try (ResultSet r = s.executeQuery("SHOW ENGINE INNODB STATUS")) {
            r.next();
            for (String line : r.getString("Status").split("\n")) {
                String[][] keys = {{"Log sequence number", "lsn"}, {"Log written up to", "written"}, {"Log flushed up to", "flushed"},
                        {"Pages flushed up to", "pagesFlushed"}, {"Last checkpoint at", "checkpoint"}};
                for (String[] k : keys)
                    if (line.startsWith(k[0])) m.put(k[1], Long.parseLong(line.substring(k[0].length()).trim()));
            }
        }
        return m;
    }

    static long d(Map<String, Long> a, Map<String, Long> b, String k) { return b.get(k) - a.get(k); }

    static long stmtUs(Statement s, long thread, String like) throws SQLException {
        try (ResultSet r = s.executeQuery("SELECT ROUND(TIMER_WAIT/1000000) FROM performance_schema.events_statements_history WHERE THREAD_ID="
                + thread + " AND SQL_TEXT LIKE '" + like + "' ORDER BY EVENT_ID DESC LIMIT 1")) {
            r.next();
            return r.getLong(1);
        }
    }

    static String binlogPos(Statement s) throws SQLException {
        try (ResultSet r = s.executeQuery("SHOW BINARY LOG STATUS")) {
            r.next();
            return r.getString("File") + ":" + r.getLong("Position");
        }
    }

    static int cachedPages(Statement s) throws SQLException {
        return count(s, "SELECT COUNT(*) FROM information_schema.INNODB_BUFFER_PAGE WHERE TABLE_NAME='`trade`.`orders`' AND INDEX_NAME='PRIMARY'");
    }

    static int dirtyPages(Statement s) throws SQLException {
        return count(s, "SELECT COUNT(*) FROM information_schema.INNODB_BUFFER_PAGE WHERE TABLE_NAME='`trade`.`orders`' AND INDEX_NAME='PRIMARY' AND OLDEST_MODIFICATION > 0");
    }

    static int count(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) { r.next(); return r.getInt(1); }
    }

    static void printStages(Statement s, long thread, String like) throws SQLException {
        try (ResultSet r = s.executeQuery("""
                SELECT st.EVENT_NAME, ROUND(st.TIMER_WAIT/1000000) AS us
                FROM performance_schema.events_stages_history_long st
                JOIN (SELECT EVENT_ID FROM performance_schema.events_statements_history
                      WHERE THREAD_ID=%d AND SQL_TEXT LIKE '%s' ORDER BY EVENT_ID DESC LIMIT 1) q
                  ON st.NESTING_EVENT_ID = q.EVENT_ID
                WHERE st.THREAD_ID=%d ORDER BY st.EVENT_ID""".formatted(thread, like, thread))) {
            StringBuilder sb = new StringBuilder("  ");
            long total = 0;
            while (r.next()) {
                long us = r.getLong(2);
                total += us;
                sb.append(r.getString(1).replace("stage/sql/", "").replace("stage/innodb/", "innodb/"))
                  .append(' ').append(us).append(" | ");
            }
            System.out.println(sb.append("合计 ").append(total).append(" µs"));
        }
    }

    static void printRows(Statement s, String sql, String... cols) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                StringJoiner j = new StringJoiner(" | ", "  ", "");
                for (String col : cols) j.add(col + "=" + r.getString(col));
                System.out.println(j);
            }
        }
    }
}
