package labs.database.sharding_cross_shard_query;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.sql.*;
import java.util.*;

/**
 * 分片以后的难题：80 万行订单（8 张表 × 10 万），ShardingSphere-JDBC 5.5.3 + MySQL 8.4。
 *
 * <pre>
 *   load    建 t_order_0..7、t_order_item_0..7、灌数据（每张表一半老订单号、一半带基因的新订单号；9527 属于用户 1024）
 *   gene    按新订单号 / 老订单号 9527 / 订单号 + user_id 查，各发出几条 SQL；只用基因算老单号会查错表
 *   page    跨片分页：LIMIT offset, 20 的 offset 取 0 / 1000 / 10000 / 100000，耗时和 JVM 分配的内存；再和游标分页、单表比
 *   agg     AVG、GROUP BY + ORDER BY + LIMIT 被改写成了什么
 *   join    t_order JOIN t_order_item：不配绑定表 / 配绑定表，各发出几条 SQL
 *   unique  两张表里能不能有同一个 order_no
 * </pre>
 */
public class CrossShardLab {

    static final String RAW = "jdbc:mysql://127.0.0.1:33112/%s?useSSL=false&allowPublicKeyRetrieval=true";
    static final PrintStream OUT = System.out;

    public static void main(String[] args) throws Exception {
        List<String> modes = args.length > 0 && !args[0].equals("all") ? List.of(args) : List.of("load", "gene", "page", "agg", "join", "unique");
        for (String m : modes) {
            switch (m) {
                case "load" -> load();
                case "gene" -> gene();
                case "page" -> page();
                case "agg" -> agg();
                case "join" -> join();
                case "unique" -> unique();
                default -> throw new IllegalArgumentException(m);
            }
        }
    }

    static DataSource sharding(String yaml) throws Exception {
        try (InputStream in = CrossShardLab.class.getResourceAsStream("/" + yaml)) {
            return YamlShardingSphereDataSourceFactory.createDataSource(in.readAllBytes());
        }
    }

    static Connection raw(String db) throws SQLException {
        return DriverManager.getConnection(String.format(RAW, db), "root", "root");
    }

    static void title(String t) {
        System.out.flush();
        OUT.println();
        OUT.println("==== " + t);
    }

    // ---------------------------------------------------------------- load
    static void load() throws Exception {
        title("load：8 张表 × 10 万行，t_order_item 每张 1 万行");
        long t0 = System.nanoTime();
        for (int k = 0; k < 8; k++) {
            try (Connection c = raw("ds_" + (k % 2)); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS t_order_" + k);
                st.execute("CREATE TABLE t_order_" + k + " (id BIGINT PRIMARY KEY, order_no VARCHAR(24) NOT NULL, user_id BIGINT NOT NULL,"
                        + " shop_id BIGINT NOT NULL, amount_cents BIGINT NOT NULL, status VARCHAR(16) NOT NULL, created_at DATETIME NOT NULL,"
                        + " UNIQUE KEY uk_order_no (order_no), KEY idx_user_created (user_id, created_at), KEY idx_created (created_at))");
                st.execute("DROP TABLE IF EXISTS t_order_item_" + k);
                st.execute("CREATE TABLE t_order_item_" + k + " (id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, user_id BIGINT NOT NULL,"
                        + " sku_id BIGINT NOT NULL, qty INT NOT NULL, KEY idx_order (order_id), KEY idx_user (user_id))");
                st.execute("SET SESSION cte_max_recursion_depth = 200000");
                // 第 n 行：user_id = 8m + k（每张表 12500 个用户、每人 8 单）；n ≤ 5 万是老订单号（全表连续编号），之后是带基因的新订单号
                // created_at = 2026-10-01 + (8n + k) 秒：8 张表交错、全局不重复
                String idExpr = "IF(n <= 50000, " + k + " * 100000 + n, ((1000000000 + " + k + " * 100000 + n) << 3) | " + k + ")";
                st.execute("INSERT INTO t_order_" + k + " (id, order_no, user_id, shop_id, amount_cents, status, created_at)"
                        + " WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100000)"
                        + " SELECT " + idExpr + ", CONCAT('NO', " + idExpr + "), 8 * (125 + n % 12500) + " + k + ", 301 + n % 50,"
                        + " 1000 + (n * 13) % 90000, IF(n % 5 = 0, 'CREATED', 'PAID'), TIMESTAMP('2026-10-01') + INTERVAL (8 * n + " + k + ") SECOND FROM seq");
                st.execute("INSERT INTO t_order_item_" + k + " (id, order_id, user_id, sku_id, qty)"
                        + " SELECT id, id, user_id, 88, 2 FROM t_order_" + k + " WHERE id % 10 = " + k + " OR user_id IN (1024, 1025)");
            }
        }
        try (Connection c = raw("ds_0"); Statement st = c.createStatement()) {
            // 9527 是 J06 那一单：老订单号，属于用户 1024（1024 % 8 = 0，在 t_order_0）
            st.execute("UPDATE t_order_0 SET user_id = 1024 WHERE id = 9527");
            st.execute("ANALYZE TABLE t_order_0");
        }
        OUT.printf("   用了 %.1f s%n", (System.nanoTime() - t0) / 1e9);
    }

    // ---------------------------------------------------------------- gene
    static void gene() throws Exception {
        long newId = GeneShardingAlgorithm.geneId(60_000, 1024);  // 1024 的一张新单（基因 0）
        try (Connection raw = raw("ds_0"); Statement st = raw.createStatement()) {
            st.execute("UPDATE t_order_0 SET user_id = 1024 WHERE id = " + newId);
        }
        DataSource ds = sharding("gene.yaml");
        try (Connection c = ds.getConnection()) {
            title("gene 1. 只拿新订单号 " + newId + "（最低 3 位 = " + (newId % 8) + "）");
            query(c, "SELECT id, user_id FROM t_order WHERE id = ?", newId);
            title("gene 2. 只拿老订单号 9527：没有基因 → 广播");
            query(c, "SELECT id, user_id FROM t_order WHERE id = ?", 9527L);
            title("gene 3. 老订单号 + user_id：又是 1 张表");
            query(c, "SELECT id, user_id FROM t_order WHERE id = ? AND user_id = ?", 9527L, 1024L);
        }
        title("gene 4. 如果对老单号也照用基因：9527 % 8 = 7，去 t_order_7 找");
        try (Connection c = raw("ds_1"); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM t_order_7 WHERE id = 9527")) {
            try (ResultSet rs = ps.executeQuery()) { rs.next(); OUT.println("   → t_order_7 里 id = 9527 的行数：" + rs.getLong(1) + "（它其实在 t_order_0）"); }
        }
    }

    // ---------------------------------------------------------------- page
    static long allocated() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sum = 0;
        for (long b : mx.getThreadAllocatedBytes(mx.getAllThreadIds())) if (b > 0) sum += b;
        return sum;
    }

    record Run(double ms, double mb, int rows, String last) {}

    static Run measure(Connection c, String sql, Object... params) throws SQLException {
        double best = Double.MAX_VALUE, mb = 0;
        int rows = 0;
        String last = null;
        for (int r = 0; r < 4; r++) {
            long a0 = allocated(), t0 = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    rows = 0;
                    while (rs.next()) { rows++; last = rs.getString(2); }
                }
            }
            double ms = (System.nanoTime() - t0) / 1e6, m = (allocated() - a0) / 1048576.0;
            if (r > 0 && ms < best) { best = ms; mb = m; }   // 第 1 次是预热，取后 3 次里最快的
        }
        return new Run(best, mb, rows, last);
    }

    static void page() throws Exception {
        DataSource ds = sharding("gene-quiet.yaml");
        title("page：ORDER BY created_at DESC LIMIT offset, 20（80 万行，8 张表）");
        OUT.println("   offset   每张表取   耗时(ms)   JVM 分配(MB)   返回");
        String lastAt = null;
        try (Connection c = ds.getConnection()) {
            for (int off : new int[]{0, 1000, 10000, 100000}) {
                Run r = measure(c, "SELECT id, created_at, user_id, shop_id, amount_cents, status FROM t_order ORDER BY created_at DESC LIMIT ?, 20", off);
                OUT.printf("   %6d   %8d   %8.1f   %12.1f   %d 行%n", off, off + 20, r.ms, r.mb, r.rows);
                if (off == 100000) lastAt = r.last;
            }
            title("page 游标：WHERE created_at < 上一页最后一条 ORDER BY created_at DESC LIMIT 20");
            Run s = measure(c, "SELECT id, created_at, user_id, shop_id, amount_cents, status FROM t_order WHERE created_at < ? ORDER BY created_at DESC LIMIT 20", lastAt);
            OUT.printf("   从 %s 往后翻一页：%.1f ms，JVM 分配 %.1f MB，%d 行%n", lastAt, s.ms, s.mb, s.rows);
        }
        title("page 对照：单张表里 LIMIT 100000, 20（直连 MySQL，不经过 ShardingSphere）");
        try (Connection c = raw("ds_0")) {
            Run r = measure(c, "SELECT id, created_at, user_id, shop_id, amount_cents, status FROM t_order_0 ORDER BY created_at DESC LIMIT 100000, 20");
            OUT.printf("   %.1f ms，JVM 分配 %.1f MB（跳过的 10 万行在 MySQL 里数掉，不传给应用）%n", r.ms, r.mb);
        }
    }

    // ---------------------------------------------------------------- agg
    static void agg() throws Exception {
        DataSource ds = sharding("gene.yaml");
        try (Connection c = ds.getConnection()) {
            title("agg 1. AVG 被拆成 SUM + COUNT");
            query(c, "SELECT AVG(amount_cents) FROM t_order WHERE shop_id = ?", 301L);
            title("agg 2. GROUP BY + 按聚合排序 + LIMIT 3");
            query(c, "SELECT shop_id, COUNT(*) AS cnt FROM t_order GROUP BY shop_id ORDER BY cnt DESC, shop_id LIMIT 3");
        }
    }

    // ---------------------------------------------------------------- join
    static void join() throws Exception {
        for (String y : new String[]{"join-no.yaml", "join-yes.yaml"}) {
            DataSource ds = sharding(y);
            try (Connection c = ds.getConnection()) {
                title("join（" + (y.contains("yes") ? "配了绑定表" : "没配绑定表") + "）1. 两个用户");
                countRoutes(c, "SELECT o.id, i.sku_id FROM t_order o JOIN t_order_item i ON o.id = i.order_id WHERE o.user_id IN (?, ?)", 1024L, 1025L);
                title("join（" + (y.contains("yes") ? "配了绑定表" : "没配绑定表") + "）2. 没有分片条件");
                countRoutes(c, "SELECT COUNT(*) FROM t_order o JOIN t_order_item i ON o.id = i.order_id WHERE o.shop_id = ?", 301L);
            }
        }
    }

    // ---------------------------------------------------------------- unique
    static void unique() throws Exception {
        DataSource ds = sharding("gene.yaml");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            title("unique：用户 1024 和 1025 各下一单，order_no 都是 DUP-1");
            st.execute("DELETE FROM t_order WHERE user_id IN (1024, 1025) AND order_no = 'DUP-1'");
            for (long u : new long[]{1024, 1025}) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_order (id, order_no, user_id, shop_id, amount_cents, status, created_at) VALUES (?, 'DUP-1', ?, 301, 100, 'CREATED', NOW())")) {
                    ps.setLong(1, GeneShardingAlgorithm.geneId(900_000 + u, u));
                    ps.setLong(2, u);
                    ps.executeUpdate();
                    OUT.println("   → 用户 " + u + " 插入成功");
                } catch (SQLException e) {
                    OUT.println("   → 用户 " + u + " 报错：" + e.getMessage());
                }
            }
            System.out.flush();
            query(c, "SELECT COUNT(*) FROM t_order WHERE order_no = ?", "DUP-1");
        }
    }

    // ---------------------------------------------------------------- helpers
    static void query(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0, cols = rs.getMetaData().getColumnCount();
                StringBuilder first = new StringBuilder();
                while (rs.next()) {
                    if (n < 3) {
                        first.append(n == 0 ? "" : " | ");
                        for (int i = 1; i <= cols; i++) first.append(i == 1 ? "" : ",").append(rs.getString(i));
                    }
                    n++;
                }
                System.out.flush();
                OUT.println("   → 结果 " + n + " 行" + (n > 0 ? "：" + first + (n > 3 ? " …" : "") : ""));
            }
        } catch (SQLException e) {
            System.out.flush();
            OUT.println("   → 报错：" + e.getMessage());
        }
    }

    /** 把这一条的 sql-show 输出收进缓冲区，只数 Actual SQL 有几条，打印前两条。 */
    static void countRoutes(Connection c, String sql, Object... params) throws SQLException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.out.flush();
        System.setOut(new PrintStream(buf, true));
        int rows = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows++; }
        } finally {
            System.out.flush();
            System.setOut(OUT);
        }
        List<String> actual = buf.toString().lines().filter(l -> l.contains("Actual SQL:")).toList();
        OUT.println("   → 真实 SQL " + actual.size() + " 条，结果 " + rows + " 行");
        actual.stream().limit(2).forEach(l -> OUT.println("     " + (l.length() > 170 ? l.substring(0, 170) + " …" : l)));
    }
}
