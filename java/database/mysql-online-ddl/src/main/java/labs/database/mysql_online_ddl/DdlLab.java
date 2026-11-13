package labs.database.mysql_online_ddl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交易靶场 orders 表（100 万行，和全景 J06 同一份数据），一主一从：
 *   setup  在主库建表、灌 100 万行，等从库追上（每次都重建，实验之间互不影响）
 *   algos  ⑤ 一边每 20 ms 付一笔款，一边改表：INSTANT 加列 / INPLACE 加列（重建）/ INPLACE 加索引 / VARCHAR 扩长 / COPY 改类型；
 *          每种看主库用时、付款最慢多久、从库晚多久才执行完；最后数 INSTANT 的行版本上限
 *   mdl    一个没提交的事务读过 orders，ALTER 排在它后面等元数据锁，40 个用户请求又排在 ALTER 后面；
 *          修法：ALTER 设 lock_wait_timeout=1 等不到就放弃、过会儿重试
 * 由 run.sh 调用；主库 127.0.0.1:33091，从库 33092。按 JDK 8 写法（--release 8）。
 */
public class DdlLab {
    static final String OPT = "?user=root&password=root&allowPublicKeyRetrieval=true&useSSL=false";
    static final String SRC = "jdbc:mysql://127.0.0.1:33091/trade" + OPT;
    static final String REP = "jdbc:mysql://127.0.0.1:33092/trade" + OPT;

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "algos";
        if ("setup".equals(cmd)) setup();
        else if ("algos".equals(cmd)) algos();
        else if ("mdl".equals(cmd)) mdl();
        else throw new IllegalArgumentException(cmd);
    }

    // ------------------------------------------------------------------ 小工具
    static Connection src() throws SQLException { return DriverManager.getConnection(SRC); }
    static Connection rep() throws SQLException { return DriverManager.getConnection(REP); }
    static String one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.next() ? r.getString(1) : null; }
    }
    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    static double max(List<Double> xs) { return xs.isEmpty() ? 0 : Collections.max(xs); }
    static double pct(List<Double> xs, double p) {
        if (xs.isEmpty()) return 0;
        List<Double> c = new ArrayList<Double>(xs); Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) Math.ceil(p * c.size()) - 1));
    }
    /** 从库要多久才能把主库目前为止的事务都放完，单位 ms */
    static double replicaCatchUp(Connection s, Connection r) throws SQLException {
        String gtids = one(s, "SELECT @@GLOBAL.gtid_executed");
        long t0 = System.nanoTime();
        String ok = one(r, "SELECT WAIT_FOR_EXECUTED_GTID_SET('" + gtids + "', 300)");
        if (!"0".equals(ok)) throw new IllegalStateException("从库 300 秒没追上");
        return ms(t0);
    }

    // ------------------------------------------------------------------ setup
    static void setup() throws Exception {
        try (Connection c = DriverManager.getConnection(SRC.replace("/trade?", "/?")); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS trade");
            s.execute("USE trade");
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("DROP TABLE IF EXISTS t_ver");
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
            try (Connection src = src(); Connection rep = rep()) { replicaCatchUp(src, rep); }
            System.out.println("[setup] 从库已追上");
        }
    }

    // ------------------------------------------------------------------ algos：⑤ 一边付款一边改表
    /** 后台“付款”：每 20 ms 把 9527 在 CREATED / PAID 之间来回改一次，记下每笔的开始时刻和耗时 */
    static class Payer implements Runnable {
        final List<long[]> log = new CopyOnWriteArrayList<long[]>();   // {开始纳秒, 耗时纳秒}
        final AtomicBoolean stop = new AtomicBoolean();
        public void run() {
            try (Connection c = src()) {
                boolean paid = false;
                while (!stop.get()) {
                    long t0 = System.nanoTime();
                    exec(c, paid ? "UPDATE orders SET status='CREATED', paid_at=NULL WHERE id=9527"
                                 : "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527");
                    log.add(new long[]{t0, System.nanoTime() - t0});
                    paid = !paid;
                    sleep(20);
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
        /** [from, to] 之间开始、或者跨过这段时间的那些付款的耗时，ms */
        List<Double> between(long from, long to) {
            List<Double> out = new ArrayList<Double>();
            for (long[] x : log) if (x[0] <= to && x[0] + x[1] >= from) out.add(x[1] / 1e6);
            return out;
        }
    }

    static void algos() throws Exception {
        String[][] steps = {
            {"INSTANT 加列", "ALTER TABLE orders ADD COLUMN coupon_id BIGINT NULL, ALGORITHM=INSTANT"},
            {"INPLACE 加列（重建表）", "ALTER TABLE orders ADD COLUMN remark VARCHAR(64) NULL, ALGORITHM=INPLACE, LOCK=NONE"},
            {"INPLACE 加索引", "ALTER TABLE orders ADD INDEX idx_created (created_at), ALGORITHM=INPLACE, LOCK=NONE"},
            {"VARCHAR(16→63) INPLACE", "ALTER TABLE orders MODIFY status VARCHAR(63) NOT NULL, ALGORITHM=INPLACE, LOCK=NONE"},
            {"VARCHAR(63→64) INPLACE", "ALTER TABLE orders MODIFY status VARCHAR(64) NOT NULL, ALGORITHM=INPLACE, LOCK=NONE"},
            {"INT→BIGINT 要 INSTANT", "ALTER TABLE orders MODIFY qty BIGINT NOT NULL, ALGORITHM=INSTANT"},
            {"INT→BIGINT 要 INPLACE", "ALTER TABLE orders MODIFY qty BIGINT NOT NULL, ALGORITHM=INPLACE, LOCK=NONE"},
            {"INT→BIGINT 只能 COPY", "ALTER TABLE orders MODIFY qty BIGINT NOT NULL, ALGORITHM=COPY"},
        };
        try (Connection s = src(); Connection r = rep()) {
            Payer payer = new Payer();
            Thread t = new Thread(payer, "payer");
            t.start();
            sleep(1000);
            List<Double> idle = payer.between(0, System.nanoTime());
            System.out.printf("[algos] 后台每 20 ms 付一笔款（UPDATE 9527）；平时：中位 %.2f ms，最慢 %.1f ms%n",
                    pct(idle, .5), max(idle));
            for (String[] st : steps) {
                long t0 = System.nanoTime();
                String err = null;
                try { exec(s, st[1]); } catch (SQLException e) { err = e.getErrorCode() + " " + e.getMessage(); }
                long t1 = System.nanoTime();
                if (err != null) {
                    System.out.printf("  %-22s 被拒：%s%n", st[0], err);
                    continue;
                }
                sleep(300);                       // 让 DDL 结束后的那一笔付款也落进统计
                List<Double> pay = payer.between(t0, t1);
                double lag = replicaCatchUp(s, r);
                System.out.printf("  %-22s 主库 %8.1f ms | 期间付款 %3d 笔，最慢 %8.1f ms | 主库做完后从库又过了 %8.1f ms 才放完%n",
                        st[0], (t1 - t0) / 1e6, pay.size(), max(pay), lag);
            }
            payer.stop.set(true);
            t.join();
            System.out.println("  orders 现在的行版本数 TOTAL_ROW_VERSIONS = "
                    + one(s, "SELECT TOTAL_ROW_VERSIONS FROM information_schema.INNODB_TABLES WHERE NAME='trade/orders'"));

            // INSTANT 加列 / 删列各算一个行版本，数到上限为止
            exec(s, "DROP TABLE IF EXISTS t_ver");
            exec(s, "CREATE TABLE t_ver (id INT PRIMARY KEY, a INT)");
            exec(s, "INSERT INTO t_ver VALUES (1, 1), (2, 2)");
            int n = 0;
            String err = null;
            while (n < 300) {
                try {
                    exec(s, "ALTER TABLE t_ver ADD COLUMN c INT, ALGORITHM=INSTANT");
                    n++;
                    exec(s, "ALTER TABLE t_ver DROP COLUMN c, ALGORITHM=INSTANT");
                    n++;
                } catch (SQLException e) { err = e.getErrorCode() + " " + e.getMessage(); break; }
            }
            System.out.println("  小表 t_ver 反复 INSTANT 加列/删列：成功 " + n + " 次，行版本 = "
                    + one(s, "SELECT TOTAL_ROW_VERSIONS FROM information_schema.INNODB_TABLES WHERE NAME='trade/t_ver'"));
            System.out.println("    下一次：" + err);
            long t0 = System.nanoTime();
            exec(s, "ALTER TABLE t_ver ADD COLUMN c INT");   // 不写 ALGORITHM：INSTANT 不行了，自动退到 INPLACE 重建
            System.out.printf("    不写 ALGORITHM 再加一次：成功，%.1f ms，行版本 = %s%n", ms(t0),
                    one(s, "SELECT TOTAL_ROW_VERSIONS FROM information_schema.INNODB_TABLES WHERE NAME='trade/t_ver'"));
        }
    }

    // ------------------------------------------------------------------ mdl：一个没提交的事务，堵死整张表
    static final int USERS = 40;

    static void mdl() throws Exception {
        System.out.println("[mdl] 会话 A：BEGIN; SELECT ... FROM orders WHERE id=1;（不提交，比如代码里事务里调了个慢接口）");
        runMdl(0);
        System.out.println("[mdl] 修法：ALTER 前 SET SESSION lock_wait_timeout = 1，等不到锁就放弃，过 1 秒再试");
        runMdl(1);
    }

    /** timeout = 0 表示 ALTER 用默认的 lock_wait_timeout（一年）一直等 */
    static void runMdl(final int timeout) throws Exception {
        final String col = timeout == 0 ? "note1" : "note2";
        try (Connection a = src(); Connection mon = src()) {
            a.setAutoCommit(false);
            one(a, "SELECT status FROM orders WHERE id=1");
            final long[] alterWait = new long[2];   // {从发出到成功的总时长, 放弃了几次}
            final long tStart = System.nanoTime();
            Thread alter = new Thread(new Runnable() { public void run() {
                try (Connection b = src()) {
                    if (timeout > 0) exec(b, "SET SESSION lock_wait_timeout = " + timeout);
                    while (true) {
                        try {
                            exec(b, "ALTER TABLE orders ADD COLUMN " + col + " VARCHAR(64) NULL, ALGORITHM=INSTANT");
                            break;
                        } catch (SQLException e) {
                            if (e.getErrorCode() != 1205) throw e;   // Lock wait timeout exceeded
                            alterWait[1]++;
                            sleep(1000);
                        }
                    }
                    alterWait[0] = System.nanoTime() - tStart;
                } catch (SQLException e) { throw new RuntimeException(e); }
            }});
            alter.start();
            sleep(300);

            // 40 个用户请求：一半付款（各改各的订单），一半查订单
            final List<Double> lat = new CopyOnWriteArrayList<Double>();
            final CountDownLatch done = new CountDownLatch(USERS);
            for (int i = 0; i < USERS; i++) {
                final int k = i;
                new Thread(new Runnable() { public void run() {
                    try (Connection c = src()) {
                        long t0 = System.nanoTime();
                        if (k % 2 == 0) exec(c, "UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=" + (100000 + k));
                        else one(c, "SELECT status FROM orders WHERE id=" + (200000 + k));
                        lat.add(ms(t0));
                    } catch (SQLException e) { throw new RuntimeException(e); }
                    finally { done.countDown(); }
                }}).start();
                sleep(25);
            }
            sleep(500);
            // 这时候谁拿着锁、谁在等
            System.out.println("  第 " + (int) (ms(tStart) / 1000) + " 秒，performance_schema.metadata_locks 里 orders 上的元数据锁：");
            try (Statement st = mon.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT LOCK_TYPE, LOCK_STATUS, COUNT(*) FROM performance_schema.metadata_locks"
                  + " WHERE OBJECT_SCHEMA='trade' AND OBJECT_NAME='orders' GROUP BY LOCK_TYPE, LOCK_STATUS ORDER BY 2, 1")) {
                while (rs.next()) System.out.printf("    %-20s %-8s %3d 个%n", rs.getString(1), rs.getString(2), rs.getInt(3));
            }
            System.out.println("  processlist 里 'Waiting for table metadata lock' 的会话："
                    + one(mon, "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE STATE='Waiting for table metadata lock'") + " 个");
            try (Statement st = mon.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT blocking_pid, sql_kill_blocking_connection, COUNT(*) FROM sys.schema_table_lock_waits"
                  + " WHERE object_name='orders' GROUP BY 1, 2")) {
                while (rs.next()) System.out.printf("  sys.schema_table_lock_waits：%d 个等待，源头 blocking_pid = %s → %s%n",
                        rs.getInt(3), rs.getString(1), rs.getString(2));
            }
            sleep(5000 - (long) ms(tStart));
            a.commit();
            System.out.printf("  第 %.1f 秒会话 A 提交%n", ms(tStart) / 1000);
            done.await();
            alter.join();
            int slow = 0;
            for (double x : lat) if (x > 500) slow++;
            System.out.printf("  ALTER（INSTANT）从发出到完成 %.1f s（中途放弃 %d 次）%n", alterWait[0] / 1e9, alterWait[1]);
            System.out.printf("  %d 个用户请求：中位 %.0f ms，最慢 %.0f ms，超过 500 ms 的 %d 个%n",
                    USERS, pct(lat, .5), max(lat), slow);
        }
    }
}
