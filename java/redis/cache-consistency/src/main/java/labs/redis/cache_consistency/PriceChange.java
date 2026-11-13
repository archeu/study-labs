package labs.redis.cache_consistency;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 交易靶场：运营把 88 号商品从 399.00 改成 359.00，商品服务的读路径是 Cache-Aside
 * （GET product:88，没命中就 SELECT，再 SET 回 Redis，30 分钟过期）。
 * 每一轮：4 个读线程每 1 ms 读一次，写线程按某种顺序改库、动缓存；
 * 写完 300 ms 后对比 Redis 和数据库，Redis 里留着旧价就算“卡住”（要等 30 分钟过期才好）。
 *
 * 用法：java PriceChange [all | natural | forced | binlog]
 */
public class PriceChange {
    static final String REDIS = System.getProperty("redis", "redis://127.0.0.1:63798");
    static final String JDBC = System.getProperty("jdbc",
            "jdbc:mysql://127.0.0.1:33072/trade?user=root&password=root&useSSL=false&allowPublicKeyRetrieval=true");
    static final String KEY = "product:88";
    static final int TTL = 1800;
    static final int ROUNDS = Integer.getInteger("rounds", 20);

    static RedisClient client;
    static RedisCommands<String, String> R;   // 写线程和检查用的连接
    static Connection W;                      // 写线程（运营后台）的数据库连接

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        client = RedisClient.create(REDIS);
        R = client.connect().sync();
        W = DriverManager.getConnection(JDBC);
        setup();
        if (mode.equals("all") || mode.equals("natural")) natural();
        if (mode.equals("all") || mode.equals("forced")) forced();
        if (mode.equals("all") || mode.equals("binlog")) binlog();
        W.close();
        client.shutdown();
    }

    // ---------------------------------------------------------------- 数据
    static void setup() throws SQLException {
        try (Statement s = W.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS product (id BIGINT PRIMARY KEY, name VARCHAR(64), "
                    + "price DECIMAL(10,2) NOT NULL, version BIGINT NOT NULL)");
            s.execute("INSERT INTO product VALUES (88, '88 号商品', 399.00, 1) "
                    + "ON DUPLICATE KEY UPDATE price = 399.00, version = 1");
        }
    }

    /** 改价：一条自动提交的 UPDATE，版本号加一；返回新版本号。 */
    static long updatePrice(Connection c, String price) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE product SET price = ?, version = LAST_INSERT_ID(version + 1) WHERE id = 88")) {
            p.setString(1, price);
            p.executeUpdate();
        }
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 数据库里的当前值，格式和缓存里的一样：价格|v版本。 */
    static String dbValue(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT price, version FROM product WHERE id = 88")) {
            rs.next();
            return rs.getString(1) + "|v" + rs.getLong(2);
        }
    }

    /** 每轮开始：数据库回到 399.00；缓存是热的（有 399.00）还是空的（刚过期）。 */
    static void reset(boolean warm) throws SQLException {
        updatePrice(W, "399.00");
        if (warm) R.setex(KEY, TTL, dbValue(W)); else R.del(KEY);
    }

    // ---------------------------------------------------------------- 读路径（商品服务）
    // 带版本的回填：缓存里已经有更新的版本（或写线程留下的“墓碑”）就不写
    static final String GUARDED_SET =
            "local cur = redis.call('GET', KEYS[1]) "
          + "if cur then "
          + "  local v = tonumber(string.match(cur, '|v(%d+)$')) "
          + "  local tomb = string.sub(cur, 1, 1) == '#' "
          + "  local mine = tonumber(ARGV[2]) "
          + "  if v and ((tomb and v > mine) or ((not tomb) and v >= mine)) then return 0 end "
          + "end "
          + "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3]) return 1";

    static final class Reader implements Runnable {
        final Connection c;
        final RedisCommands<String, String> r;
        volatile boolean stop;
        long pauseMs;                 // 查完库、回填之前停多久（模拟 GC 停顿、慢序列化），只对第一次未命中生效
        CountDownLatch selected;      // 第一次查完库就倒数，让写线程在这之后再动手
        boolean guarded;              // 用带版本的回填
        boolean once;                 // 只读一次就退出

        Reader() throws SQLException {
            c = DriverManager.getConnection(JDBC);
            r = client.connect().sync();
        }

        public void run() {
            try {
                do { readOnce(); sleep(1); } while (!stop && !once);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        void readOnce() throws SQLException {
            String v = r.get(KEY);
            if (v != null && v.charAt(0) != '#') return;            // 命中
            String fresh = dbValue(c);                               // 没命中：查库
            if (selected != null) { selected.countDown(); selected = null; }
            if (pauseMs > 0) { sleep(pauseMs); pauseMs = 0; }
            if (guarded) {
                String ver = fresh.substring(fresh.indexOf("|v") + 2);
                r.eval(GUARDED_SET, ScriptOutputType.INTEGER, new String[]{KEY}, fresh, ver, String.valueOf(TTL));
            } else {
                r.setex(KEY, TTL, fresh);                            // 回填
            }
        }

        void close() throws SQLException { c.close(); }
    }

    // ---------------------------------------------------------------- 写路径（运营后台改价）
    interface Writer { void write(String price) throws Exception; }

    static Writer deleteFirst()  { return p -> { R.del(KEY); updatePrice(W, p); }; }
    static Writer updateDelete() { return p -> { updatePrice(W, p); R.del(KEY); }; }
    static Writer updateSet()    { return p -> { long v = updatePrice(W, p); R.setex(KEY, TTL, p + "|v" + v); }; }
    static Writer doubleDelete(long delayMs) {
        return p -> { R.del(KEY); updatePrice(W, p); sleep(delayMs); R.del(KEY); };
    }
    /** 改库后不删，留一个带新版本号的墓碑（60 秒），配合带版本的回填。 */
    static Writer tombstone() {
        return p -> { long v = updatePrice(W, p); R.setex(KEY, 60, "#|v" + v); };
    }

    /** Redis 里留着和数据库不一样的值 = 卡住。key 不存在不算（下一次读会重建）。 */
    static boolean stuck() throws SQLException {
        String cached = R.get(KEY);
        return cached != null && cached.charAt(0) != '#' && !cached.equals(dbValue(W));
    }

    // ---------------------------------------------------------------- 第一组：不做手脚，各跑 20 轮
    static void natural() throws Exception {
        System.out.println("== natural：4 个读线程每 1 ms 读一次，不做任何手脚，每种 " + ROUNDS + " 轮");
        System.out.println("   写法                                   缓存开始时   卡住轮数");
        runNatural("① 先删缓存，再改库", deleteFirst(), true);
        runNatural("② 先改库，再删缓存", updateDelete(), true);
        runNatural("② 先改库，再删缓存", updateDelete(), false);
        runNatural("③ 先删 → 改库 → 等 100 ms → 再删", doubleDelete(100), true);
        runNatural("④ 先改库，再 SET 新值（两个运营同时改）", null, true);
    }

    static void runNatural(String name, Writer w, boolean warm) throws Exception {
        int stuck = 0;
        long writeNs = 0;
        for (int i = 0; i < ROUNDS; i++) {
            reset(warm);
            List<Reader> rs = new ArrayList<>();
            List<Thread> ts = new ArrayList<>();
            for (int k = 0; k < 4; k++) {
                Reader r = new Reader();
                rs.add(r);
                Thread t = new Thread(r);
                ts.add(t);
                t.start();
            }
            sleep(20);
            long t0 = System.nanoTime();
            if (w != null) {
                w.write("359.00");
            } else {
                twoAdmins(0);
            }
            writeNs += System.nanoTime() - t0;
            sleep(300);
            for (Reader r : rs) r.stop = true;
            for (Thread t : ts) t.join();
            for (Reader r : rs) r.close();
            if (stuck()) stuck++;
        }
        System.out.printf("   %-34s %-6s       %2d / %d   （写一次平均 %.1f ms）%n",
                name, warm ? "热" : "空", stuck, ROUNDS, writeNs / 1e6 / ROUNDS);
    }

    /** 两个运营几乎同时改价：A 改成 359.00，B 改成 329.00，各自改完库就 SET。A 在 SET 前停 pauseMs。 */
    static void twoAdmins(long pauseMs) throws Exception {
        final Connection cb = DriverManager.getConnection(JDBC);
        final RedisCommands<String, String> rb = client.connect().sync();
        final CountDownLatch aCommitted = new CountDownLatch(1);
        Thread a = new Thread(() -> {
            try {
                long v = updatePrice(W, "359.00");
                aCommitted.countDown();
                sleep(pauseMs);
                R.setex(KEY, TTL, "359.00|v" + v);
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        Thread b = new Thread(() -> {
            try {
                if (pauseMs > 0) aCommitted.await();
                long v = updatePrice(cb, "329.00");
                rb.setex(KEY, TTL, "329.00|v" + v);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        a.start(); b.start(); a.join(); b.join();
        cb.close();
    }

    // ---------------------------------------------------------------- 第二组：把最坏的先后顺序排出来
    static void forced() throws Exception {
        System.out.println("== forced：缓存刚过期，一个读线程查完库后停 P ms 才回填（GC 停顿、慢序列化），写线程等它查完库再改价");
        forcedRound("② 先改库，再删缓存                ", updateDelete(), 50, false);
        forcedRound("③ 延迟双删，等 100 ms，读停 50 ms   ", doubleDelete(100), 50, false);
        forcedRound("③ 延迟双删，等 100 ms，读停 150 ms  ", doubleDelete(100), 150, false);
        forcedRound("⑤ 改库后留墓碑 + 带版本回填，读停 150 ms", tombstone(), 150, true);
        reset(false);
        twoAdmins(30);
        System.out.printf("   %-38s Redis=%s  DB=%s  %s%n", "④ 两个运营，A 改完库停 30 ms 才 SET",
                R.get(KEY), dbValue(W), stuck() ? "卡住" : "一致");
    }

    static void forcedRound(String name, Writer w, long pauseMs, boolean guarded) throws Exception {
        reset(false);
        Reader slow = new Reader();
        slow.pauseMs = pauseMs;
        slow.guarded = guarded;
        slow.once = true;
        CountDownLatch selected = new CountDownLatch(1);
        slow.selected = selected;
        Thread t = new Thread(slow);
        t.start();
        selected.await();
        w.write("359.00");
        t.join();
        slow.close();
        String cached = R.get(KEY);
        // 写完以后再来一个普通读请求，看它读到什么
        Reader after = new Reader();
        after.guarded = guarded;
        after.readOnce();
        after.close();
        System.out.printf("   %-38s 慢读回填后 Redis=%-12s 再读一次后 Redis=%-12s DB=%s  %s%n",
                name, cached, R.get(KEY), dbValue(W), stuck() ? "卡住" : "一致");
    }

    // ---------------------------------------------------------------- 第三组：订阅 binlog 删缓存
    static void binlog() throws Exception {
        System.out.println("== binlog：一个伪装成从库的客户端读 binlog，看到 trade.product 的 UPDATE 就 DEL product:<id>");
        final RedisCommands<String, String> rl = client.connect().sync();
        final Map<Long, String> tables = new HashMap<>();
        final List<Long> delAt = Collections.synchronizedList(new ArrayList<>());
        BinaryLogClient bl = new BinaryLogClient("127.0.0.1", 33072, "root", "root");
        bl.setServerId(9001);
        bl.registerEventListener(ev -> {
            EventData d = ev.getData();
            if (d instanceof TableMapEventData) {
                TableMapEventData t = (TableMapEventData) d;
                tables.put(t.getTableId(), t.getDatabase() + "." + t.getTable());
            } else if (d instanceof UpdateRowsEventData) {
                UpdateRowsEventData u = (UpdateRowsEventData) d;
                if (!"trade.product".equals(tables.get(u.getTableId()))) return;
                for (Map.Entry<Serializable[], Serializable[]> row : u.getRows()) {
                    rl.del("product:" + row.getValue()[0]);
                }
                delAt.add(System.nanoTime());
            }
        });
        bl.connect(10_000);

        // 1) 改完库进程就挂了，业务代码里的 DEL 没执行（这里直接不删）
        reset(true);
        updatePrice(W, "359.00");
        sleep(500);
        System.out.printf("   改完库没删缓存（模拟进程挂了），500 ms 后 Redis 里 product:88 %s%n",
                R.exists(KEY) == 0 ? "已被 binlog 订阅者删掉" : "还在：" + R.get(KEY));

        // 2) 提交到 DEL 的延迟，20 次
        long[] lag = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            reset(true);
            sleep(50);
            delAt.clear();
            long c;
            try (PreparedStatement p = W.prepareStatement("UPDATE product SET price = ?, version = version + 1 WHERE id = 88")) {
                p.setString(1, i % 2 == 0 ? "359.00" : "369.00");
                p.executeUpdate();                 // 自动提交，返回时已提交
                c = System.nanoTime();
            }
            while (delAt.isEmpty()) Thread.sleep(0, 200_000);
            lag[i] = Math.max(0, delAt.get(0) - c) / 1000;   // DEL 有可能比 JDBC 拿到“提交成功”还早
        }
        Arrays.sort(lag);
        System.out.printf("   提交返回 → binlog 订阅者删完：p50 %.1f ms，最大 %.1f ms（%d 次）%n",
                lag[lag.length / 2] / 1000.0, lag[lag.length - 1] / 1000.0, ROUNDS);

        // 3) 订阅 binlog 也挡不住“慢读回填”
        reset(false);
        Reader slow = new Reader();
        slow.pauseMs = 50;
        slow.once = true;
        CountDownLatch selected = new CountDownLatch(1);
        slow.selected = selected;
        Thread t = new Thread(slow);
        t.start();
        selected.await();
        updatePrice(W, "359.00");   // 业务代码不删，全靠 binlog
        t.join();
        slow.close();
        System.out.printf("   缓存刚过期 + 读停 50 ms 才回填：Redis=%s  DB=%s  %s%n", R.get(KEY), dbValue(W),
                stuck() ? "卡住（binlog 的 DEL 早到了 40 多 ms，回填在它后面）" : "一致");
        bl.disconnect();
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
