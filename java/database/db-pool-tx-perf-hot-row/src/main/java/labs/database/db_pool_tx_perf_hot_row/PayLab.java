package labs.database.db_pool_tx_perf_hot_row;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 交易靶场 · 20:00 秒杀 88 号商品：64 个 Tomcat 线程同时付款，每笔付款一个事务。
 *
 *   setup  建 trade 库：orders 20 万行、inventory（88 号库存）、pay_log
 *   hot    热点行：风控接口（20 ms）放在事务里 / 挪出事务、库存先改 / 最后改、池子 10 / 50
 *   size   没有热点行：池子 1～64，看吞吐、延迟、每次提交摊到几次 redo fsync
 *
 * 用 JDBC 手写事务，等价于 Spring 的 @Transactional：借连接 → setAutoCommit(false) → SQL → commit → 还连接。
 */
public class PayLab {
    static final String URL = System.getProperty("url",
            "jdbc:mysql://127.0.0.1:33093/trade?useSSL=false&allowPublicKeyRetrieval=true");
    static final int THREADS = 64;          // 模拟 Tomcat 线程
    static final int RISK_MS = 20;          // 风控接口耗时
    static final int ORDERS = 200_000;

    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "hot";
        int secs = a.length > 1 ? Integer.parseInt(a[1]) : 8;
        if (mode.equals("setup")) { setup(); return; }
        if (mode.equals("hot")) {
            System.out.println("[hot] 64 个线程连续付款，每笔：改订单 + 写流水 + 扣 88 号库存（同一行），风控 20 ms；每组 " + secs + " s");
            System.out.println("  组                               池  付款/秒   平均   中位    p99 | 等连接 等锁(扣库存) 提交 | 池里排队峰值 InnoDB锁等待峰值 | binlog sync/提交");
            String[] risk = {"风控在事务里", "风控挪出事务"}, hot = {"库存先改", "库存最后改"};
            int no = 1;
            for (int r = 0; r < 2; r++) for (int h = 0; h < 2; h++) for (int p : new int[]{10, 50})
                run(new Cfg((no++) + " " + risk[r] + " · " + hot[h], p, r == 0, h == 0, false), secs);
        } else if (mode.equals("size")) {
            System.out.println("[size] 64 个线程连续付款，每笔：改一行随机订单 + 写流水，没有热点行、没有外部调用；每组 " + secs + " s");
            System.out.println("  池   付款/秒   平均   中位    p99 | 等连接  在库里 | redo fsync/提交  binlog sync/提交  Threads_running 均值");
            for (int p : new int[]{1, 2, 4, 8, 16, 32, 64}) run(new Cfg("size", p, false, false, true), secs);
        }
    }

    static class Cfg {
        final String name; final int pool; final boolean riskInTx, hotFirst, noHot;
        Cfg(String n, int p, boolean r, boolean h, boolean nh) { name = n; pool = p; riskInTx = r; hotFirst = h; noHot = nh; }
    }

    static Connection side() throws SQLException { return DriverManager.getConnection(URL, "root", "root"); }

    static void setup() throws Exception {
        try (Connection c = DriverManager.getConnection(URL.replace("/trade", "/"), "root", "root");
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS trade");
            s.execute("CREATE DATABASE trade");
            s.execute("USE trade");
            s.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL," +
                    " amount DECIMAL(10,2) NOT NULL, updated_at DATETIME(6) NOT NULL, KEY idx_user(user_id))");
            s.execute("SET SESSION cte_max_recursion_depth = 1000000");
            s.execute("INSERT INTO orders WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < " + ORDERS + ")" +
                    " SELECT i, i % 50000, 'CREATED', 176.00, NOW(6) FROM n");
            s.execute("CREATE TABLE inventory (sku INT PRIMARY KEY, stock INT NOT NULL)");
            s.execute("INSERT INTO inventory VALUES (88, 100000000), (89, 100000000)");
            s.execute("CREATE TABLE pay_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL," +
                    " amount DECIMAL(10,2) NOT NULL, created_at DATETIME(6) NOT NULL)");
        }
        System.out.println("[setup] trade.orders " + ORDERS + " 行，inventory 88 号库存，pay_log 空表");
    }

    static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static long status(Statement s, String name) throws SQLException {
        try (ResultSet r = s.executeQuery("SHOW GLOBAL STATUS LIKE '" + name + "'")) { return r.next() ? r.getLong(2) : -1; }
    }

    static long binlogSyncs(Statement s) throws SQLException {
        // binlog 文件上的 sync 次数：file_summary_by_event_name 的 MISC 里含 sync；用 events_waits 太重，这里取 COUNT_MISC 作近似
        try (ResultSet r = s.executeQuery("SELECT COUNT_MISC FROM performance_schema.file_summary_by_event_name" +
                " WHERE EVENT_NAME = 'wait/io/file/sql/binlog'")) { return r.next() ? r.getLong(1) : -1; }
    }

    static void run(Cfg cfg, int secs) throws Exception {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(URL); hc.setUsername("root"); hc.setPassword("root");
        hc.setMaximumPoolSize(cfg.pool); hc.setMinimumIdle(cfg.pool);
        hc.setConnectionTimeout(30_000);      // 默认值
        hc.setPoolName("pay");
        HikariDataSource ds = new HikariDataSource(hc);
        HikariPoolMXBean mx = ds.getHikariPoolMXBean();
        // 先把连接都建好，不算进测量
        List<Connection> warm = new ArrayList<>();
        for (int i = 0; i < cfg.pool; i++) warm.add(ds.getConnection());
        for (Connection c : warm) c.close();

        final AtomicBoolean measuring = new AtomicBoolean(false), stop = new AtomicBoolean(false);
        final LongAdder done = new LongAdder(), acqNs = new LongAdder(), hotNs = new LongAdder(), commitNs = new LongAdder(), inDbNs = new LongAdder();
        final ConcurrentLinkedQueue<long[]> lats = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                Random rnd = new Random(tid);
                long[] buf = new long[200_000]; int n = 0;
                while (!stop.get()) {
                    long t0 = System.nanoTime();
                    if (!cfg.riskInTx && !cfg.noHot) sleep(RISK_MS);          // 风控在事务外：先调，再借连接
                    long tA = System.nanoTime();
                    try (Connection c = ds.getConnection()) {
                        long tB = System.nanoTime();
                        c.setAutoCommit(false);
                        long id = 1 + rnd.nextInt(ORDERS);
                        long hot = 0;
                        try (PreparedStatement inv = c.prepareStatement("UPDATE inventory SET stock = stock - 2 WHERE sku = 88");
                             PreparedStatement ord = c.prepareStatement("UPDATE orders SET status = 'PAID', updated_at = NOW(6) WHERE id = ?");
                             PreparedStatement log = c.prepareStatement("INSERT INTO pay_log(order_id, amount, created_at) VALUES (?, 176.00, NOW(6))")) {
                            if (!cfg.noHot && cfg.hotFirst) { long h = System.nanoTime(); inv.executeUpdate(); hot = System.nanoTime() - h; }
                            ord.setLong(1, id); ord.executeUpdate();
                            log.setLong(1, id); log.executeUpdate();
                            if (cfg.riskInTx) sleep(RISK_MS);                   // 风控在事务里：占着连接（库存先改时还占着 88 号的锁）
                            if (!cfg.noHot && !cfg.hotFirst) { long h = System.nanoTime(); inv.executeUpdate(); hot = System.nanoTime() - h; }
                            long tC = System.nanoTime();
                            c.commit();
                            long tD = System.nanoTime();
                            c.setAutoCommit(true);
                            if (measuring.get()) {
                                acqNs.add(tB - tA); hotNs.add(hot); commitNs.add(tD - tC); inDbNs.add(tD - tB);
                                done.increment();
                                if (n < buf.length) buf[n++] = tD - t0;
                            }
                        } catch (SQLException e) {
                            c.rollback(); c.setAutoCommit(true);
                        }
                    } catch (SQLException e) {
                        // 等连接超时（30 s）才会到这里；本实验里不会发生
                    }
                }
                lats.add(Arrays.copyOf(buf, n));
                return null;
            });
        }
        sleep(1000);   // 预热
        long w0, b0;
        try (Connection c = side(); Statement s = c.createStatement()) {
            w0 = status(s, "Innodb_os_log_fsyncs"); b0 = binlogSyncs(s);
            measuring.set(true);
            long end = System.currentTimeMillis() + secs * 1000L;
            int peakWait = 0, peakLock = 0; long runSum = 0, runN = 0;
            while (System.currentTimeMillis() < end) {
                peakWait = Math.max(peakWait, mx.getThreadsAwaitingConnection());
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'")) {
                    r.next(); peakLock = Math.max(peakLock, r.getInt(1));
                }
                runSum += status(s, "Threads_running") - 1; runN++;   // 减掉旁路这条连接自己
                sleep(100);
            }
            measuring.set(false);
            long fsyncs = status(s, "Innodb_os_log_fsyncs") - w0, bsync = binlogSyncs(s) - b0;
            stop.set(true);
            pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS);
            long cnt = done.sum();
            long[] all = lats.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            double p50 = all.length == 0 ? 0 : all[all.length / 2] / 1e6, p99 = all.length == 0 ? 0 : all[(int) (all.length * 0.99)] / 1e6;
            double tps = cnt / (double) secs;
            double avg = Arrays.stream(all).average().orElse(0) / 1e6;
            if (cfg.noHot) {
                System.out.printf("  %3d  %7.0f %6.1f %6.1f %6.1f | %6.1f %6.1f | %15.2f %16.2f %18.1f%n", cfg.pool, tps, avg, p50, p99,
                        acqNs.sum() / 1e6 / cnt, inDbNs.sum() / 1e6 / cnt, fsyncs / (double) cnt, bsync / (double) cnt, runSum / (double) runN);
            } else {
                System.out.printf("  %-24s %3d  %7.0f %6.1f %6.1f %6.1f | %6.1f %10.1f %5.1f | %12d %15d | %15.2f%n", cfg.name, cfg.pool, tps, avg, p50, p99,
                        acqNs.sum() / 1e6 / cnt, hotNs.sum() / 1e6 / cnt, commitNs.sum() / 1e6 / cnt, peakWait, peakLock, bsync / (double) cnt);
            }
        }
        ds.close();
        sleep(500);
    }
}
