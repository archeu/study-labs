package labs.database.sharding_shardingsphere;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * 订单表拆成 8 张：ShardingSphere-JDBC 5.5.3 把一条逻辑 SQL 改写成几条真实 SQL。
 *
 * <pre>
 *   show    建表、插入、按用户 / 订单号 / 商家 / IN / BETWEEN / 改分片键 / 跨片分页 / 全局表 JOIN，
 *           每条都打印 ShardingSphere 的 "Logic SQL" 和 "Actual SQL"（sql-show）
 *   spread  1 万个用户各下一单：规则 A（库表同源，% 2 + % 8）和规则 B（% 2 + % 4）每张真实表各有几行
 * </pre>
 * 先跑 ./run.sh 起 MySQL（127.0.0.1:33111，库 ds_0 ds_1 bad_0 bad_1）。
 */
public class ShardingLab {

    static final String RAW = "jdbc:mysql://127.0.0.1:33111/?useSSL=false&allowPublicKeyRetrieval=true";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        if (mode.equals("show") || mode.equals("all")) show();
        if (mode.equals("spread") || mode.equals("all")) spread();
    }

    static DataSource sharding(String yaml) throws Exception {
        try (InputStream in = ShardingLab.class.getResourceAsStream("/" + yaml)) {
            return YamlShardingSphereDataSourceFactory.createDataSource(in.readAllBytes());
        }
    }

    static void title(String t) {
        System.out.println();
        System.out.println("==== " + t);
        System.out.flush();
    }

    // ---------------------------------------------------------------- show
    static void show() throws Exception {
        DataSource ds = sharding("sharding-good.yaml");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            title("0. 逻辑建表：一条 CREATE TABLE t_order 变成 8 条");
            st.execute("DROP TABLE IF EXISTS t_order");
            st.execute("CREATE TABLE t_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL,"
                    + " sku_id BIGINT, qty INT, amount_cents BIGINT, status VARCHAR(16), region_code INT, created_at DATETIME(6),"
                    + " KEY idx_user_created (user_id, created_at), KEY idx_shop_created (shop_id, created_at))");
            st.execute("DROP TABLE IF EXISTS t_region");
            st.execute("CREATE TABLE t_region (code INT PRIMARY KEY, name VARCHAR(32))");
            st.execute("INSERT INTO t_region VALUES (310000, '上海'), (440300, '深圳'), (110000, '北京')");

            title("1. 插入：24 个用户各 3 单（只打印 1024 的第一单）");
            insertOrders(c);

            title("2. 按用户查：带分片键 → 1 条");
            query(c, "SELECT id, status FROM t_order WHERE user_id = ? ORDER BY created_at DESC LIMIT 0, 20", 1024L);

            title("3. 只按订单号查：没有分片键 → 全路由 8 条");
            query(c, "SELECT id, user_id FROM t_order WHERE id = ?", 9527L);

            title("4. 按订单号 + 用户查：又回到 1 条");
            query(c, "SELECT id, status FROM t_order WHERE id = ? AND user_id = ?", 9527L, 1024L);

            title("5. 按商家查：shop_id 不是分片键 → 8 条");
            query(c, "SELECT COUNT(*) FROM t_order WHERE shop_id = ?", 301L);

            title("6. IN 两个用户 → 只去两张表");
            query(c, "SELECT id FROM t_order WHERE user_id IN (?, ?)", 1024L, 1001L);

            title("6b. IN 三个同余的用户（1024、1032、1040 对 8 取余都是 0）→ 1 张表");
            query(c, "SELECT COUNT(*) FROM t_order WHERE user_id IN (?, ?, ?)", 1024L, 1032L, 1040L);

            title("7. 分片键上的范围：INLINE 算法默认不支持");
            query(c, "SELECT id FROM t_order WHERE user_id BETWEEN ? AND ?", 1001L, 1003L);

            title("8. 分片键套函数：路由算不出来 → 8 条");
            query(c, "SELECT id FROM t_order WHERE user_id + 0 = ?", 1024L);

            title("9. 改分片键：1024 → 1025（会换表）");
            update(c, "UPDATE t_order SET user_id = ? WHERE id = ? AND user_id = ?", 1025L, 9527L, 1024L);

            title("10. 改分片键：改成原值（不换表）");
            update(c, "UPDATE t_order SET user_id = ?, status = 'PAID' WHERE id = ? AND user_id = ?", 1024L, 9527L, 1024L);

            title("11. 跨片分页：LIMIT 4, 2 → 每张表 LIMIT 0, 6");
            query(c, "SELECT id, created_at FROM t_order ORDER BY created_at DESC LIMIT 4, 2");

            title("12. 和全局表 JOIN：t_region 每个库都有一份，在本库里 JOIN");
            query(c, "SELECT o.id, r.name FROM t_order o JOIN t_region r ON o.region_code = r.code WHERE o.user_id = ?", 1024L);
        }
    }

    static void insertOrders(Connection c) throws SQLException {
        String sql = "INSERT INTO t_order (id, user_id, shop_id, sku_id, qty, amount_cents, status, region_code, created_at)"
                + " VALUES (?, ?, ?, 88, 2, 79800, 'CREATED', ?, ?)";
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 1, 20, 0);
        long id = 10_000;
        int[] regions = {310000, 440300, 110000};
        Logger.mute(true);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int round = 0; round < 3; round++) {
                for (long u = 1001; u <= 1024; u++) {
                    long oid = (u == 1024 && round == 0) ? 9527 : ++id;   // 9527 是 J06 那一单
                    if (u == 1024 && round == 0) Logger.mute(false);
                    ps.setLong(1, oid);
                    ps.setLong(2, u);
                    ps.setLong(3, 301 + (u % 3));
                    ps.setInt(4, regions[(int) (u % 3)]);
                    ps.setTimestamp(5, Timestamp.valueOf(t0.plusMinutes(round * 30L + (u - 1000))));
                    ps.executeUpdate();
                    if (u == 1024 && round == 0) Logger.mute(true);
                }
            }
        }
        Logger.mute(false);
    }

    static void query(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0;
                StringBuilder first = new StringBuilder();
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    if (n < 3) {
                        first.append(n == 0 ? "" : " | ");
                        for (int i = 1; i <= cols; i++) first.append(i == 1 ? "" : ",").append(rs.getString(i));
                    }
                    n++;
                }
                System.out.flush();
                System.out.println("   → 结果 " + n + " 行" + (n > 0 ? "：" + first + (n > 3 ? " …" : "") : ""));
            }
        } catch (SQLException e) {
            System.out.flush();
            System.out.println("   → 报错：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void update(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            int n = ps.executeUpdate();
            System.out.flush();
            System.out.println("   → 更新 " + n + " 行");
        } catch (SQLException e) {
            System.out.flush();
            System.out.println("   → 报错：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- spread
    static void spread() throws Exception {
        Logger.mute(true);
        String[][] rules = {{"sharding-good.yaml", "规则 A：库 % 2，表 % 8（同一个余数）", "ds_0", "ds_1"},
                            {"sharding-bad.yaml", "规则 B：库 % 2，表 % 4（两个余数相关）", "bad_0", "bad_1"}};
        for (String[] r : rules) {
            DataSource ds = sharding(r[0]);
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS t_order");
                st.execute("CREATE TABLE t_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, created_at DATETIME(6))");
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_order (id, user_id, shop_id, created_at) VALUES (?, ?, ?, NOW(6))")) {
                    for (long u = 100_001; u <= 110_000; u++) {
                        ps.setLong(1, u);
                        ps.setLong(2, u);
                        ps.setLong(3, 301);
                        ps.addBatch();
                        if (u % 1000 == 0) ps.executeBatch();
                    }
                }
                c.commit();
            }
            Logger.mute(true);
            Logger.mute(false);
            title(r[1] + "：1 万个用户各一单");
            try (Connection raw = DriverManager.getConnection(RAW, "root", "root"); Statement st = raw.createStatement()) {
                for (String db : new String[]{r[2], r[3]}) {
                    Map<String, Long> m = new TreeMap<>();
                    try (ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + db + "' AND table_name LIKE 't\\_order\\_%'")) {
                        while (rs.next()) m.put(rs.getString(1), 0L);
                    }
                    for (String t : m.keySet()) {
                        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + db + "." + t)) {
                            rs.next();
                            m.put(t, rs.getLong(1));
                        }
                    }
                    StringBuilder sb = new StringBuilder("   " + db + "：");
                    m.forEach((t, n) -> sb.append(t).append('=').append(n).append("  "));
                    System.out.println(sb);
                }
            }
        }
    }

    /** slf4j-simple 只在启动时读级别；用一个开关把插入 1 万行时的 sql-show 输出挡掉。 */
    static final class Logger {
        static final java.io.PrintStream REAL = System.out;
        static final java.io.PrintStream NULL = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
        static void mute(boolean on) {
            System.out.flush();
            System.setOut(on ? NULL : REAL);
        }
    }
}
