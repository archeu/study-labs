package labs.database.mysql_84_upgrade;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 付款服务的连接矩阵：两个账号（老账号 mysql_native_password、新账号 caching_sha2_password）
 * × 三种 JDBC URL 写法，各连一次，连上就付一笔款（全景 J06 那条 UPDATE）。
 *
 * 用法：
 *   ConnectMatrix matrix <port> <标签>   跑矩阵
 *   ConnectMatrix cache  <port>          caching_sha2_password 的缓存：FLUSH PRIVILEGES 后，useSSL=false 时好时坏
 *
 * 驱动不写死：classpath 上放 Connector/J 9.x 还是老的 5.1.x，DriverManager 都能找到。
 */
public class ConnectMatrix {

    static final String[][] URLS = {
            {"老写法 useSSL=false", "useSSL=false"},
            {"默认（走 TLS）", ""},
            {"useSSL=false + allowPublicKeyRetrieval", "useSSL=false&allowPublicKeyRetrieval=true"},
    };
    static final String[] USERS = {"pay_native", "pay_sha2"};

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        if ("matrix".equals(mode)) {
            matrix(port, args.length > 2 ? args[2] : "");
        } else if ("cache".equals(mode)) {
            cache(port);
        } else {
            throw new IllegalArgumentException(mode);
        }
    }

    static String url(int port, String params) {
        String base = "jdbc:mysql://127.0.0.1:" + port + "/shop?connectTimeout=3000";
        return params.isEmpty() ? base : base + "&" + params;
    }

    /** 连一次，连上就付一笔款；返回一行结果。 */
    static String tryPay(int port, String user, String params) {
        try (Connection c = DriverManager.getConnection(url(port, params), user, "Pay#2026");
             Statement s = c.createStatement()) {
            int n = s.executeUpdate("UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'");
            s.executeUpdate("UPDATE orders SET status='CREATED', paid_at=NULL WHERE id=9527"); // 复原，下一格还能付
            String ver;
            try (ResultSet rs = s.executeQuery("SELECT @@version")) {
                rs.next();
                ver = rs.getString(1);
            }
            return "OK    付款改了 " + n + " 行（server " + ver + "）";
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage()).split("\n")[0];
            if (msg.length() > 110) msg = msg.substring(0, 110) + "…";
            return "FAIL  " + e.getClass().getSimpleName() + " [" + e.getSQLState() + "/" + e.getErrorCode() + "] " + msg;
        }
    }

    static void matrix(int port, String label) throws Exception {
        String driver = DriverManager.getDriver(url(port, "")).getClass().getName();
        java.sql.Driver d = DriverManager.getDriver(url(port, ""));
        System.out.printf("== %s · 驱动 %s %d.%d%n", label, driver, d.getMajorVersion(), d.getMinorVersion());
        for (String user : USERS) {
            for (String[] u : URLS) {
                flush(port); // 每格之前清空 caching_sha2 的缓存，等同于“数据库刚重启”
                System.out.printf("  %-10s | %-38s | %s%n", user, u[0], tryPay(port, user, u[1]));
            }
        }
    }

    static void flush(int port) throws SQLException {
        try (Connection root = DriverManager.getConnection(url(port, ""), "root", "root");
             Statement s = root.createStatement()) {
            s.execute("FLUSH PRIVILEGES");
        }
    }

    static void cache(int port) throws Exception {
        String plain = "useSSL=false";
        System.out.println("== caching_sha2_password 的缓存（账号 pay_sha2，URL 写 useSSL=false）");
        flush(port);
        System.out.println("  1. FLUSH PRIVILEGES 之后（等同于重启后缓存是空的）: " + tryPay(port, "pay_sha2", plain));
        System.out.println("  2. 走 TLS 登录一次                                : " + tryPay(port, "pay_sha2", ""));
        System.out.println("  3. 再用 useSSL=false                              : " + tryPay(port, "pay_sha2", plain));
        flush(port);
        System.out.println("  4. 又一次 FLUSH PRIVILEGES 之后                   : " + tryPay(port, "pay_sha2", plain));
    }
}
