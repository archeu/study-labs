package labs.database.mysql_to_tidb_pitfalls;

import java.sql.*;
import java.util.*;

/**
 * 代码没改，行为变了：同样的 SQL 在 MySQL 8.4 和 TiDB 8.5.8 上各跑一遍。
 *
 * <pre>
 *   autoinc   两台 TiDB server 交替插入：AUTO_INCREMENT 的 id 怎么跳；AUTO_ID_CACHE=1 以后；重启一台 TiDB 以后。MySQL 对照
 *   hotspot   递增主键 vs AUTO_RANDOM：把表预先切成 8 个 Region（按主键均分），各插 2 万行，看每个 Region 分到多少、leader 在哪台 TiKV
 *   bigtxn    一条 INSERT … SELECT 写约 30 MB：tidb2（单事务上限 10 MiB）报什么错，tidb1（默认）能不能过，tidb_dml_type='bulk' 呢；单行 7 MB 呢
 *   iso       T1 用 SELECT … FOR UPDATE 锁住 k 在 10～20 的范围，T2 插入 k = 15：MySQL 被间隙锁挡住，TiDB 直接成功；T1 再读一次看到什么
 * </pre>
 * TiDB 集群用 ../distributed-db-tidb-oceanbase/cluster/cluster.sh up 起；MySQL 8.4 在 127.0.0.1:33115。
 */
public class PitfallsLab {

    static final String TIDB1 = "jdbc:mysql://127.0.0.1:4001/%s?useSSL=false&allowPublicKeyRetrieval=true";
    static final String TIDB2 = "jdbc:mysql://127.0.0.1:4002/%s?useSSL=false&allowPublicKeyRetrieval=true";
    static final String MYSQL = "jdbc:mysql://127.0.0.1:33115/%s?useSSL=false&allowPublicKeyRetrieval=true";
    static final String DOCKER = System.getenv().getOrDefault("DOCKER", "docker");

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        setup();
        if (mode.equals("all") || mode.equals("autoinc")) autoinc();
        if (mode.equals("all") || mode.equals("hotspot")) hotspot();
        if (mode.equals("all") || mode.equals("bigtxn")) bigtxn();
        if (mode.equals("all") || mode.equals("iso")) iso();
    }

    static Connection tidb1() throws SQLException { return DriverManager.getConnection(String.format(TIDB1, "lab"), "root", ""); }
    static Connection tidb2() throws SQLException { return DriverManager.getConnection(String.format(TIDB2, "lab"), "root", ""); }
    static Connection mysql() throws SQLException { return DriverManager.getConnection(String.format(MYSQL, "lab"), "root", "root"); }

    static void setup() throws SQLException {
        try (Connection c = DriverManager.getConnection(String.format(TIDB1, ""), "root", ""); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS lab");
        }
        try (Connection c = DriverManager.getConnection(String.format(MYSQL, ""), "root", "root"); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS lab");
        }
    }

    static void title(String t) { System.out.println(); System.out.println("==== " + t); }

    static void exec(Connection c, String... sqls) throws SQLException {
        try (Statement st = c.createStatement()) { for (String s : sqls) st.execute(s); }
    }

    static List<String> col(Connection c, String sql) throws SQLException {
        List<String> r = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) sb.append(i == 1 ? "" : ":").append(rs.getString(i));
                r.add(sb.toString());
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- autoinc
    static void autoinc() throws Exception {
        String ddl = "CREATE TABLE t_inc (id BIGINT AUTO_INCREMENT PRIMARY KEY, via VARCHAR(8))";
        title("autoinc 1：两台 TiDB server 交替各插 3 行（默认 AUTO_ID_CACHE，每台一次缓存 3 万个）");
        try (Connection a = tidb1(); Connection b = tidb2()) {
            exec(a, "DROP TABLE IF EXISTS t_inc", ddl);
            for (int i = 0; i < 3; i++) {
                exec(a, "INSERT INTO t_inc (via) VALUES ('tidb1')");
                exec(b, "INSERT INTO t_inc (via) VALUES ('tidb2')");
            }
            System.out.println("   插入顺序是 tidb1、tidb2、tidb1、tidb2…；按 id 排序：" + col(a, "SELECT id, via FROM t_inc ORDER BY id"));
        }
        title("autoinc 2：AUTO_ID_CACHE = 1（中心化分配，v6.5 起保证递增）");
        try (Connection a = tidb1(); Connection b = tidb2()) {
            exec(a, "DROP TABLE IF EXISTS t_inc1", "CREATE TABLE t_inc1 (id BIGINT AUTO_INCREMENT PRIMARY KEY, via VARCHAR(8)) AUTO_ID_CACHE = 1");
            for (int i = 0; i < 3; i++) {
                exec(a, "INSERT INTO t_inc1 (via) VALUES ('tidb1')");
                exec(b, "INSERT INTO t_inc1 (via) VALUES ('tidb2')");
            }
            System.out.println("   " + col(a, "SELECT id, via FROM t_inc1 ORDER BY id"));
        }
        title("autoinc 3：重启 tidb1，再从 tidb1 插一行（默认缓存模式的表）");
        new ProcessBuilder((DOCKER + " restart tidb1").split(" ")).redirectErrorStream(true).start().waitFor();
        for (int i = 0; i < 60; i++) { try (Connection a = tidb1()) { break; } catch (SQLException e) { Thread.sleep(1000); } }
        try (Connection a = tidb1()) {
            exec(a, "INSERT INTO t_inc (via) VALUES ('tidb1')");
            System.out.println("   重启后 tidb1 插入的 id：" + col(a, "SELECT MAX(id) FROM t_inc"));
        }
        title("autoinc 对照：MySQL 8.4，两个连接交替插入");
        try (Connection a = mysql(); Connection b = mysql()) {
            exec(a, "DROP TABLE IF EXISTS t_inc", ddl);
            for (int i = 0; i < 3; i++) {
                exec(a, "INSERT INTO t_inc (via) VALUES ('conn1')");
                exec(b, "INSERT INTO t_inc (via) VALUES ('conn2')");
            }
            System.out.println("   " + col(a, "SELECT id, via FROM t_inc ORDER BY id"));
        }
    }

    // ---------------------------------------------------------------- hotspot
    static void hotspot() throws Exception {
        try (Connection c = tidb1()) {
            exec(c, "DROP TABLE IF EXISTS h_seq", "DROP TABLE IF EXISTS h_rand",
                    "CREATE TABLE h_seq (id BIGINT AUTO_INCREMENT PRIMARY KEY, pad VARCHAR(64))",
                    "CREATE TABLE h_rand (id BIGINT AUTO_RANDOM PRIMARY KEY, pad VARCHAR(64))",
                    // 按主键把正数范围均分成 8 个 Region
                    "SPLIT TABLE h_seq BETWEEN (0) AND (9223372036854775807) REGIONS 8",
                    "SPLIT TABLE h_rand BETWEEN (0) AND (9223372036854775807) REGIONS 8");
            Thread.sleep(3000);
            for (String t : new String[]{"h_seq", "h_rand"}) {
                long t0 = System.nanoTime();
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t + " (pad) VALUES (?)")) {
                    for (int i = 0; i < 20000; i++) {
                        ps.setString(1, "order-" + i);
                        ps.addBatch();
                        if (i % 500 == 499) { ps.executeBatch(); c.commit(); }
                    }
                }
                c.setAutoCommit(true);
                title(String.format("hotspot：%s 插 2 万行（每 500 行一个事务），%.1f s", t.equals("h_seq") ? "AUTO_INCREMENT" : "AUTO_RANDOM", (System.nanoTime() - t0) / 1e9));
                // 主键按 2^63 / 8 一段，对应预先切好的 8 个 Region
                List<String> b = col(c, "SELECT id DIV 1152921504606846976 AS region_no, COUNT(*) FROM " + t + " GROUP BY region_no ORDER BY region_no");
                System.out.println("   第几个 Region : 行数 → " + b);
                System.out.println("   前 3 个 id：" + col(c, "SELECT id FROM " + t + " ORDER BY id LIMIT 3"));
            }
            title("hotspot：两张表各 8 个 Region 的 leader 在哪台 TiKV");
            for (String t : new String[]{"h_seq", "h_rand"}) {
                System.out.println("   " + t + "：" + col(c, "SELECT GROUP_CONCAT(s.ADDRESS ORDER BY r.START_KEY SEPARATOR ' ') FROM information_schema.TIKV_REGION_STATUS r"
                        + " JOIN information_schema.TIKV_REGION_PEERS p ON p.REGION_ID = r.REGION_ID AND p.IS_LEADER = 1"
                        + " JOIN information_schema.TIKV_STORE_STATUS s ON s.STORE_ID = p.STORE_ID"
                        + " WHERE r.DB_NAME = 'lab' AND r.TABLE_NAME = '" + t + "' AND r.IS_INDEX = 0"));
            }
        }
    }

    // ---------------------------------------------------------------- bigtxn
    static void tryRun(String who, Connection c, String sql) {
        long t0 = System.nanoTime();
        try (Statement st = c.createStatement()) {
            int n = st.executeUpdate(sql);
            System.out.printf("   %s：成功，写入 %d 行，%.1f s%n", who, n, (System.nanoTime() - t0) / 1e9);
        } catch (SQLException e) {
            String m = e.getMessage();
            System.out.printf("   %s：报错 [%d] %s%n", who, e.getErrorCode(), m.length() > 160 ? m.substring(0, 160) + " …" : m);
        }
    }

    static void bigtxn() throws Exception {
        String fill = "INSERT INTO t_big WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 30000) SELECT n, REPEAT('x', 1000) FROM s";
        try (Connection a = tidb1(); Connection b = tidb2()) {
            exec(a, "DROP TABLE IF EXISTS t_big", "CREATE TABLE t_big (id BIGINT PRIMARY KEY, pad VARCHAR(1000))");
            exec(a, "SET SESSION cte_max_recursion_depth = 40000");
            exec(b, "SET SESSION cte_max_recursion_depth = 40000");
            title("bigtxn 1：一条 INSERT … SELECT，3 万行 × 1000 字节 ≈ 30 MB");
            tryRun("tidb2（txn-total-size-limit = 10 MiB）", b, fill);
            exec(a, "TRUNCATE TABLE t_big");
            tryRun("tidb1（默认配置）", a, fill);
            exec(a, "TRUNCATE TABLE t_big");
            exec(b, "SET SESSION tidb_dml_type = 'bulk'");
            tryRun("tidb2 + tidb_dml_type = 'bulk'（Pipelined DML，v8.5 仍是实验特性）", b, fill);
            exec(b, "SET SESSION tidb_dml_type = 'standard'");
            title("bigtxn 2：单行 7 MB（txn-entry-size-limit 默认 6 MiB）");
            exec(a, "DROP TABLE IF EXISTS t_blob", "CREATE TABLE t_blob (id BIGINT PRIMARY KEY, data LONGTEXT)");
            tryRun("tidb1 插一行 7 MB", a, "INSERT INTO t_blob VALUES (1, REPEAT('y', 7 * 1024 * 1024))");
        }
        try (Connection m = mysql()) {
            exec(m, "DROP TABLE IF EXISTS t_big", "CREATE TABLE t_big (id BIGINT PRIMARY KEY, pad VARCHAR(1000))", "SET SESSION cte_max_recursion_depth = 40000");
            title("bigtxn 对照：MySQL 8.4 同一条 INSERT … SELECT");
            tryRun("MySQL 8.4", m, fill);
        }
    }

    // ---------------------------------------------------------------- iso
    static void iso(String who, Connection t1, Connection t2) throws Exception {
        exec(t1, "DROP TABLE IF EXISTS t_iso", "CREATE TABLE t_iso (id INT PRIMARY KEY, k INT, KEY idx_k (k))", "INSERT INTO t_iso VALUES (10, 10), (20, 20)");
        exec(t2, "SET SESSION innodb_lock_wait_timeout = 3");
        title("iso（" + who + "）：T1 锁住 k 在 10～20 的范围，T2 插入 k = 15");
        t1.setAutoCommit(false);
        System.out.println("   T1 SELECT … FOR UPDATE：" + col(t1, "SELECT id, k FROM t_iso WHERE k BETWEEN 10 AND 20 FOR UPDATE"));
        long t0 = System.nanoTime();
        try (Statement st = t2.createStatement()) {
            st.executeUpdate("INSERT INTO t_iso VALUES (15, 15)");
            System.out.printf("   T2 INSERT k = 15：成功，%.0f ms%n", (System.nanoTime() - t0) / 1e6);
        } catch (SQLException e) {
            System.out.printf("   T2 INSERT k = 15：等了 %.1f s 后报错 [%d] %s%n", (System.nanoTime() - t0) / 1e9, e.getErrorCode(), e.getMessage());
        }
        System.out.println("   T1 再普通读一次：" + col(t1, "SELECT id, k FROM t_iso WHERE k BETWEEN 10 AND 20"));
        System.out.println("   T1 再 FOR UPDATE 读一次：" + col(t1, "SELECT id, k FROM t_iso WHERE k BETWEEN 10 AND 20 FOR UPDATE"));
        t1.commit();
        t1.setAutoCommit(true);
        System.out.println("   隔离级别：" + col(t1, "SELECT @@transaction_isolation"));
    }

    static void iso() throws Exception {
        try (Connection a = mysql(); Connection b = mysql()) { iso("MySQL 8.4", a, b); }
        try (Connection a = tidb1(); Connection b = tidb2()) {
            System.out.println();
            System.out.println("   TiDB 事务模式：" + col(a, "SELECT @@tidb_txn_mode"));
            iso("TiDB 8.5.8", a, b);
        }
    }
}
