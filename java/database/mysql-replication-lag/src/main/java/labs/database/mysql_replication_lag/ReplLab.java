package labs.database.mysql_replication_lag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.protocol.ServerSessionStateController;

/**
 * 交易靶场 orders 表（100 万行，和全景 J06 同一份数据），一主一从，GTID 异步复制：
 *   setup  在主库建表、灌 100 万行，等从库追上
 *   stale  ⑤ 付完款马上读从库：隔 0 / 5 / 20 / 50 ms 读，读到旧值的比例；修法一读主库，修法二等自己的 GTID 到从库
 *   bigtx  30 万行一个事务 vs 拆成 60 批：心跳表量真实延迟，对照 Seconds_Behind_Source
 *   break  在从库上删一行，主库再改它：复制停下、报 1032；打开 super_read_only 后再试
 * 由 run.sh 调用；主库 127.0.0.1:33081，从库 33082。按 JDK 8 写法（--release 8）。
 */
public class ReplLab {
    static final String OPT = "?user=root&password=root&allowPublicKeyRetrieval=true&useSSL=false";
    static final String SRC = "jdbc:mysql://127.0.0.1:33081/trade" + OPT;
    static final String REP = "jdbc:mysql://127.0.0.1:33082/trade" + OPT;

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "stale";
        if ("setup".equals(cmd)) setup();
        else if ("stale".equals(cmd)) stale();
        else if ("bigtx".equals(cmd)) bigtx();
        else if ("break".equals(cmd)) brk();
        else throw new IllegalArgumentException(cmd);
    }

    // ------------------------------------------------------------------ 小工具
    static Connection src() throws SQLException { return DriverManager.getConnection(SRC); }
    static Connection rep() throws SQLException { return DriverManager.getConnection(REP); }
    static String one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.next() ? r.getString(1) : null; }
    }
    static int upd(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { return s.executeUpdate(sql); }
    }
    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    /** SHOW REPLICA STATUS 里挑几列 */
    static Map<String, String> replicaStatus(Connection r) throws SQLException {
        Map<String, String> m = new LinkedHashMap<String, String>();
        try (Statement s = r.createStatement(); ResultSet rs = s.executeQuery("SHOW REPLICA STATUS")) {
            if (!rs.next()) return m;
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i), rs.getString(i));
        }
        return m;
    }
    /** 等从库把主库目前为止的事务都回放完 */
    static void waitSync(Connection s, Connection r) throws SQLException {
        String gtids = one(s, "SELECT @@GLOBAL.gtid_executed");
        String ok = one(r, "SELECT WAIT_FOR_EXECUTED_GTID_SET('" + gtids + "', 120)");
        if (!"0".equals(ok)) throw new IllegalStateException("从库 120 秒没追上");
    }
    static double pct(List<Double> xs, double p) {
        List<Double> c = new ArrayList<Double>(xs); Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) Math.ceil(p * c.size()) - 1));
    }

    // ------------------------------------------------------------------ setup
    static void setup() throws Exception {
        try (Connection c = DriverManager.getConnection(SRC.replace("/trade?", "/?")); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS trade");
            s.execute("USE trade");
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("CREATE TABLE orders ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " order_no VARCHAR(20) NOT NULL,"
                    + " user_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, qty INT NOT NULL,"
                    + " amount_cents BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
                    + " created_at DATETIME(6) NOT NULL, paid_at DATETIME(6) NULL) ENGINE=InnoDB");
            s.execute("CREATE TABLE IF NOT EXISTS heartbeat (id INT PRIMARY KEY, ts DATETIME(6) NOT NULL)");
            s.execute("REPLACE INTO heartbeat VALUES (1, NOW(6))");
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
            System.out.printf("[setup] 主库灌 100 万行，用时 %.1f s%n", (System.nanoTime() - t0) / 1e9);
            long t1 = System.nanoTime();
            try (Connection src = src(); Connection rep = rep()) { waitSync(src, rep); }
            System.out.printf("[setup] 从库追上，又等了 %.1f s%n", (System.nanoTime() - t1) / 1e9);
        }
    }

    // ------------------------------------------------------------------ stale：⑤ 付完款马上读从库
    static final String PAY = "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'";
    static final String RESET = "UPDATE orders SET status='CREATED', paid_at=NULL WHERE id=9527";
    static final String READ = "SELECT status FROM orders WHERE id=9527";
    static final int ROUNDS = 200;

    static void stale() throws Exception {
        try (Connection s = src(); Connection r = rep()) {
            System.out.println("[stale] 主库付款（自动提交），隔一会儿去从库读 9527；每种间隔 " + ROUNDS + " 次");
            for (int gap : new int[]{0, 5, 20, 50}) {
                int old = 0;
                for (int i = 0; i < ROUNDS; i++) {
                    upd(s, RESET); waitSync(s, r);
                    upd(s, PAY);
                    if (gap > 0) sleep(gap);
                    if ("CREATED".equals(one(r, READ))) old++;
                }
                System.out.printf("  隔 %2d ms 读从库：%3d / %d 次读到 CREATED（%.0f%%）%n", gap, old, ROUNDS, 100.0 * old / ROUNDS);
            }
            // 从库多久能看见：commit 返回后一直轮询从库
            List<Double> seen = new ArrayList<Double>();
            for (int i = 0; i < ROUNDS; i++) {
                upd(s, RESET); waitSync(s, r);
                upd(s, PAY);
                long t0 = System.nanoTime();
                while (!"PAID".equals(one(r, READ))) { /* 轮询 */ }
                seen.add(ms(t0));
            }
            System.out.printf("  commit 返回到从库读到 PAID：中位 %.1f ms，p99 %.1f ms，最长 %.1f ms%n",
                    pct(seen, .5), pct(seen, .99), pct(seen, 1));

            // 修法一：写完的这一个请求读主库
            List<Double> a = new ArrayList<Double>(); int wrongA = 0;
            for (int i = 0; i < ROUNDS; i++) {
                upd(s, RESET); waitSync(s, r);
                upd(s, PAY);
                long t0 = System.nanoTime();
                if (!"PAID".equals(one(s, READ))) wrongA++;
                a.add(ms(t0));
            }
            System.out.printf("[stale] 修法一 写后读主库：%d 次读到 CREATED，读一次中位 %.2f ms%n", wrongA, pct(a, .5));

            // 修法二：拿到自己这笔事务的 GTID，到从库上等它回放完再读（session_track_gtids=OWN_GTID）
            try (Connection s2 = DriverManager.getConnection(SRC + "&trackSessionState=true")) {
                upd(s2, "SET SESSION session_track_gtids = OWN_GTID");
                ServerSessionStateController ctl = s2.unwrap(JdbcConnection.class).getServerSessionStateController();
                List<Double> w = new ArrayList<Double>(); int wrongB = 0, timeouts = 0;
                String sample = null;
                for (int i = 0; i < ROUNDS; i++) {
                    upd(s2, RESET); waitSync(s2, r);
                    upd(s2, PAY);
                    String gtid = ownGtid(ctl);
                    if (sample == null) sample = gtid;
                    long t0 = System.nanoTime();
                    try (PreparedStatement p = r.prepareStatement("SELECT WAIT_FOR_EXECUTED_GTID_SET(?, 1)")) {
                        p.setString(1, gtid);
                        try (ResultSet rs = p.executeQuery()) { rs.next(); if (rs.getInt(1) != 0) timeouts++; }
                    }
                    if (!"PAID".equals(one(r, READ))) wrongB++;
                    w.add(ms(t0));
                }
                System.out.printf("[stale] 修法二 等自己的 GTID（例如 %s）：%d 次读到 CREATED，%d 次超时；等 + 读 中位 %.1f ms，p99 %.1f ms%n",
                        sample, wrongB, timeouts, pct(w, .5), pct(w, .99));
            }
        }
    }

    static String ownGtid(ServerSessionStateController ctl) {
        ServerSessionStateController.ServerSessionStateChanges ch = ctl.getSessionStateChanges();
        if (ch != null) {
            for (ServerSessionStateController.SessionStateChange c : ch.getSessionStateChangesList()) {
                if (c.getType() == ServerSessionStateController.SESSION_TRACK_GTIDS) return c.getValues().get(0);
            }
        }
        throw new IllegalStateException("服务器没回 GTID：session_track_gtids 没打开？");
    }

    // ------------------------------------------------------------------ bigtx：大事务 vs 拆批
    static void bigtx() throws Exception {
        try (Connection s = src(); Connection r = rep()) {
            waitSync(s, r);
            for (int round = 0; round < 2; round++) {
                final boolean big = round == 0;
                final AtomicBoolean stop = new AtomicBoolean();
                // 心跳：主库每 100 ms 写一次当前时间（自动提交），从库上 NOW(6) - ts 就是真实延迟（两个容器共用宿主机时钟）
                Thread hb = new Thread(new Runnable() { public void run() {
                    try (Connection h = src()) {
                        while (!stop.get()) { upd(h, "UPDATE heartbeat SET ts=NOW(6) WHERE id=1"); sleep(100); }
                    } catch (SQLException e) { throw new RuntimeException(e); }
                }});
                final List<String> samples = Collections.synchronizedList(new ArrayList<String>());
                final double[] maxLag = {0}, maxSbs = {0};
                final long start = System.nanoTime();
                Thread mon = new Thread(new Runnable() { public void run() {
                    try (Connection m = rep()) {
                        while (!stop.get()) {
                            double lag = Double.parseDouble(one(m, "SELECT TIMESTAMPDIFF(MICROSECOND, ts, NOW(6)) / 1e6 FROM heartbeat WHERE id=1"));
                            String sbs = replicaStatus(m).get("Seconds_Behind_Source");
                            maxLag[0] = Math.max(maxLag[0], lag);
                            if (sbs != null) maxSbs[0] = Math.max(maxSbs[0], Double.parseDouble(sbs));
                            samples.add(String.format("%.1f\t%.2f\t%s", ms(start) / 1000, lag, sbs));
                            sleep(250);
                        }
                    } catch (SQLException e) { throw new RuntimeException(e); }
                }});
                hb.start(); sleep(300); mon.start();      // 先让心跳写一次，免得第一次读到上一轮留下的旧时间
                sleep(1000);
                long t0 = System.nanoTime();
                double commitAt;
                if (big) {
                    s.setAutoCommit(false);
                    upd(s, "UPDATE orders SET qty = qty + 1 WHERE id <= 300000");
                    s.commit();
                    s.setAutoCommit(true);
                } else {
                    for (int b = 0; b < 60; b++) {
                        upd(s, "UPDATE orders SET qty = qty + 1 WHERE id > " + (b * 5000) + " AND id <= " + ((b + 1) * 5000));
                    }
                }
                double srcSec = ms(t0) / 1000;
                commitAt = ms(start) / 1000;
                // 等从库追上，再多看 1 秒
                waitSync(s, r);
                double caught = ms(start) / 1000;
                sleep(1000);
                stop.set(true); hb.join(); mon.join();
                System.out.printf("[bigtx] %s：主库用时 %.1f s（第 %.1f s 提交完）；从库第 %.1f s 追上 | 心跳延迟最大 %.1f s | Seconds_Behind_Source 最大 %.0f%n",
                        big ? "30 万行一个事务" : "拆成 60 批 × 5000 行", srcSec, commitAt, caught, maxLag[0], maxSbs[0]);
                System.out.println("  时间(s)\t心跳延迟(s)\tSeconds_Behind_Source");
                for (String line : samples) System.out.println("  " + line);
            }
        }
    }

    // ------------------------------------------------------------------ break：从库被人写了一笔
    static void brk() throws Exception {
        try (Connection s = src(); Connection r = rep()) {
            waitSync(s, r);
            System.out.println("[break] 从库 super_read_only=" + one(r, "SELECT @@GLOBAL.super_read_only")
                    + "，root 在从库上 DELETE id=9530");
            upd(r, "DELETE FROM orders WHERE id=9530");
            upd(s, "UPDATE orders SET status='PAID' WHERE id=9530");
            sleep(2000);
            Map<String, String> st = replicaStatus(r);
            System.out.printf("  主库改 9530 之后：IO=%s SQL=%s Seconds_Behind_Source=%s%n  Last_SQL_Errno=%s%n  Last_SQL_Error=%s%n",
                    st.get("Replica_IO_Running"), st.get("Replica_SQL_Running"), st.get("Seconds_Behind_Source"),
                    st.get("Last_SQL_Errno"), st.get("Last_SQL_Error"));
            upd(s, "UPDATE orders SET status='PAID' WHERE id=9540");
            sleep(1000);
            System.out.println("  主库接着改 9540，从库上 9540 = " + one(r, "SELECT status FROM orders WHERE id=9540")
                    + "（主库 = " + one(s, "SELECT status FROM orders WHERE id=9540") + "）");
            System.out.println("  从库 gtid_executed 多出一个自己的 UUID（errant 事务）：" + one(r, "SELECT REPLACE(@@GLOBAL.gtid_executed, '\\n', ' ')"));

            // 修复：把那一行补回来，让 applier 接着跑；然后打开 super_read_only 再试
            upd(r, "SET SESSION sql_log_bin=0");
            String row = one(s, "SELECT CONCAT_WS(',', id, QUOTE(order_no), user_id, sku_id, qty, amount_cents, QUOTE(status), QUOTE(created_at), 'NULL') FROM orders WHERE id=9530");
            upd(r, "INSERT INTO orders VALUES (" + row + ")");
            upd(r, "START REPLICA SQL_THREAD");
            waitSync(s, r);
            System.out.println("  补回 9530 再 START REPLICA SQL_THREAD：追上了，从库 9540 = " + one(r, "SELECT status FROM orders WHERE id=9540"));
            upd(r, "SET GLOBAL super_read_only = ON");
            try {
                upd(r, "DELETE FROM orders WHERE id=9530");
                System.out.println("  super_read_only=ON 后 root 还能删？");
            } catch (SQLException e) {
                System.out.println("  super_read_only=ON 后 root 再删：" + e.getErrorCode() + " " + e.getMessage());
            }
            upd(s, "UPDATE orders SET status='CREATED' WHERE id IN (9530, 9540)");
            waitSync(s, r);
            System.out.println("  主库的改动照常回放：从库 9530 = " + one(r, "SELECT status FROM orders WHERE id=9530"));
            upd(r, "SET GLOBAL super_read_only = OFF");
        }
    }
}
