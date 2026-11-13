package labs.java.pano_j07_product_detail_cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.BooleanListOutput;
import io.lettuce.core.output.BooleanOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全景 J07：一次商品详情查询。交易靶场的商品服务，查 88 号商品要走三级：
 * Caffeine（进程内）→ Redis → MySQL；运营改价以后，三级缓存怎么跟上。
 *
 * <pre>
 *   setup  建 trade.product 灌 1 万行，往 Redis 布隆过滤器里放 1 万个 id
 *   trace  追踪一次全冷的查询：12 站里查询那 8 站各花多少时间，Redis 里留下了什么
 *   bench  三条路径各跑几千次：本地缓存命中 / Redis 命中 / 直接查库
 *   hot    击穿：88 号缓存刚失效，4 个实例各 50 个线程同时来查，数据库被查了几次
 *   price  改价：399.00 → 359.00，两个实例分别过了多久才读到新价（有、没有 Pub/Sub 两种）
 * </pre>
 * 每个“实例”是这个 JVM 里的一个 {@link Instance} 对象：自己的 Caffeine、自己的 Redis 连接、自己的连接池，
 * 用来模拟线上 4 台商品服务。
 */
public class ProductDetail {

    static final String REDIS = System.getProperty("redis", "redis://127.0.0.1:63797");
    static final String JDBC = System.getProperty("jdbc",
            "jdbc:mysql://127.0.0.1:33071/trade?user=root&password=root&useSSL=false&allowPublicKeyRetrieval=true");
    static final long SKU = 88;
    static final String CHANNEL = "cache:evict";

    // ---------- 一台商品服务 ----------
    static final class Instance implements AutoCloseable {
        final String name;
        final RedisClient client;
        final StatefulRedisConnection<String, String> conn;  // 整个实例共用一条 TCP 连接（Lettuce 默认）
        final RedisCommands<String, String> redis;
        final HikariDataSource db;
        final Cache<String, String> l1;
        final AtomicInteger dbQueries = new AtomicInteger();
        StatefulRedisPubSubConnection<String, String> sub;
        volatile long lastEvictNanos;

        Instance(String name, Duration l1Ttl) {
            this.name = name;
            client = RedisClient.create(REDIS);
            conn = client.connect();
            redis = conn.sync();
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(JDBC);
            hc.setMaximumPoolSize(10);
            hc.setPoolName(name);
            db = new HikariDataSource(hc);
            // 第 1、8 站：进程内缓存。最多 1 万个商品，写入后 l1Ttl 过期（Pub/Sub 漏了消息时的兜底）
            l1 = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(l1Ttl).recordStats().build();
        }

        /** 第 11 站：订阅失效广播，收到哪个 key 就把自己 Caffeine 里的那一条丢掉。 */
        void subscribe() {
            sub = client.connectPubSub();
            sub.addListener(new RedisPubSubAdapter<>() {
                @Override public void message(String channel, String key) {
                    l1.invalidate(key);
                    lastEvictNanos = System.nanoTime();
                }
            });
            sub.sync().subscribe(CHANNEL);
        }

        /** 完整的读路径（第 1～8 站），带重建锁。返回 JSON，商品不存在返回 null。 */
        String get(long id) {
            String key = "product:" + id;
            // Caffeine.get(key, loader)：同一个实例里，同一个 key 同时只有一个线程去加载，其他线程等它的结果
            return l1.get(key, k -> loadFromRedisOrDb(id, true));
        }

        /** 第 2～7 站。withLock=false 时不抢重建锁，用来对比击穿。 */
        String loadFromRedisOrDb(long id, boolean withLock) {
            String key = "product:" + id;
            String v = redis.get(key);                                // 第 2、3 站
            if (v != null) return v;
            if (!bfExists(redis, id)) return null;                    // 第 4 站：布隆过滤器说没有，就一定没有，不用查库
            if (!withLock) return rebuild(id, key);
            String lockKey = "lock:" + key, token = UUID.randomUUID().toString();
            for (int i = 0; i < 100; i++) {
                if ("OK".equals(redis.set(lockKey, token, SetArgs.Builder.nx().px(3000)))) {   // 第 5 站
                    try {
                        v = redis.get(key);                           // 拿到锁再看一眼：可能别人刚建好
                        return v != null ? v : rebuild(id, key);
                    } finally {
                        unlock(lockKey, token);
                    }
                }
                sleep(10);                                            // 没抢到：等 10 ms 再读缓存
                v = redis.get(key);
                if (v != null) return v;
            }
            return rebuild(id, key);                                  // 等了 1 秒还没有，自己查（降级）
        }

        /** 第 6、7 站：查库，回填 Redis。TTL 30 分钟再加 0～5 分钟随机，避免一批 key 同时过期（雪崩）。 */
        String rebuild(long id, String key) {
            String json = selectProduct(id);
            if (json != null) redis.set(key, json, SetArgs.Builder.ex(1800 + ThreadLocalRandom.current().nextInt(300)));
            return json;
        }

        String selectProduct(long id) {
            dbQueries.incrementAndGet();
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT id, name, price, stock, detail FROM product WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return "{\"id\":" + rs.getLong(1) + ",\"name\":\"" + rs.getString(2) + "\",\"price\":\"" + rs.getBigDecimal(3)
                            + "\",\"stock\":" + rs.getInt(4) + ",\"detail\":\"" + rs.getString(5) + "\"}";
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        /** 第 7 站：只删自己加的锁。判断和删除放进一段 Lua，Redis 执行它时不会插进别的命令。 */
        static final String UNLOCK = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        void unlock(String lockKey, String token) {
            redis.eval(UNLOCK, ScriptOutputType.INTEGER, new String[]{lockKey}, token);
        }

        @Override public void close() {
            if (sub != null) sub.close();
            conn.close();
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            db.close();
        }
    }

    // ---------- Redis 布隆过滤器（Redis 8 自带 BF.* 命令）----------
    record Kw(String name) implements ProtocolKeyword {
        public byte[] getBytes() { return name.getBytes(StandardCharsets.US_ASCII); }
        public String toString() { return name; }
    }
    static boolean bfExists(RedisCommands<String, String> r, long id) {
        // Lettuce 默认用 RESP3 握手，Redis 8 在 RESP3 下对 BF.* 回布尔值；BooleanOutput 两种协议都能接
        return r.dispatch(new Kw("BF.EXISTS"), new BooleanOutput<>(StringCodec.UTF8),
                new CommandArgs<>(StringCodec.UTF8).addKey("bf:product").add(id));
    }

    // ---------- 各段 ----------
    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "all" : args[0]) {
            case "setup" -> setup();
            case "trace" -> trace();
            case "bench" -> bench();
            case "hot" -> hot();
            case "price" -> price();
            default -> { setup(); trace(); bench(); hot(); price(); }
        }
    }

    static void setup() throws Exception {
        try (HikariDataSource ds = ds(); Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS product");
            st.execute("""
                CREATE TABLE product (
                  id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL, price DECIMAL(10,2) NOT NULL,
                  stock INT NOT NULL, detail VARCHAR(1024) NOT NULL,
                  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6))""");
            c.setAutoCommit(false);
            // 详情文字约 200 个汉字，序列化成 JSON 约 700 字节，和真实商品详情页的量级差不多
            String detail = "这是靶场里的示例商品，详情页文字包括规格、材质、产地、售后说明。".repeat(6);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO product(id,name,price,stock,detail) VALUES (?,?,?,?,?)")) {
                for (int i = 1; i <= 10_000; i++) {
                    ps.setLong(1, i);
                    ps.setString(2, i + " 号商品");
                    ps.setBigDecimal(3, i == SKU ? new java.math.BigDecimal("399.00") : new java.math.BigDecimal(10 + i % 990 + ".00"));
                    ps.setInt(4, 500);
                    ps.setString(5, detail);
                    ps.addBatch();
                    if (i % 1000 == 0) ps.executeBatch();
                }
            }
            c.commit();
        }
        RedisClient client = RedisClient.create(REDIS);
        try (var conn = client.connect()) {
            var r = conn.sync();
            r.flushall();
            // 预计放 10 万个商品、误判率 0.1%
            r.dispatch(new Kw("BF.RESERVE"), new StatusOutput<>(StringCodec.UTF8),
                    new CommandArgs<>(StringCodec.UTF8).addKey("bf:product").add(0.001).add(100_000));
            for (int base = 1; base <= 10_000; base += 1000) {
                CommandArgs<String, String> a = new CommandArgs<>(StringCodec.UTF8).addKey("bf:product");
                for (int i = base; i < base + 1000; i++) a.add(i);
                r.dispatch(new Kw("BF.MADD"), new BooleanListOutput<>(StringCodec.UTF8), a);
            }
            r.configResetstat();
        } finally { client.shutdown(); }
        System.out.println("setup: product 10000 行，88 号 399.00；bf:product 放进 1..10000");
    }

    /** 一次全冷的查询，逐站计时。 */
    static void trace() throws Exception {
        try (Instance a = new Instance("A", Duration.ofSeconds(5)); Instance b = new Instance("B", Duration.ofSeconds(5))) {
            // 先用别的商品把同一条路径走 500 遍：JIT、类加载、连接池建连、Lettuce 握手、Lua 脚本编译都不算进 88 号的计时
            for (int i = 0; i < 500; i++) {
                long id = 5000 + i;
                a.redis.del("product:" + id);
                traceOnce(a, id, false);
                b.get(id);
                a.get(id);
            }
            a.dbQueries.set(0);
            String key = "product:" + SKU;
            a.redis.del(key);
            a.redis.configResetstat();
            System.out.println("== trace：实例 A 第一次查 88 号（本地、Redis 都是空的）");
            traceOnce(a, SKU, true);

            long s = System.nanoTime(); a.get(SKU);
            System.out.printf("== 实例 A 第二次查：Caffeine 命中 %.1f µs%n", (System.nanoTime() - s) / 1e3);
            s = System.nanoTime(); b.get(SKU);
            System.out.printf("== 实例 B 第一次查：本地 miss → Redis 命中 %.0f µs（一次 GET 往返）%n", (System.nanoTime() - s) / 1e3);

            System.out.println("== Redis 里留下的");
            System.out.println("   OBJECT ENCODING product:88 = " + a.redis.objectEncoding(key)
                    + "，STRLEN = " + a.redis.strlen(key) + "，MEMORY USAGE = " + a.redis.memoryUsage(key) + " 字节，TTL = " + a.redis.ttl(key) + " s");
            System.out.println("   EXISTS lock:product:88 = " + a.redis.exists("lock:" + key));
            System.out.println("   BF.EXISTS 99999999（不存在的商品）= " + bfExists(a.redis, 99_999_999));
            for (String line : a.redis.info("commandstats").split("\r?\n"))
                if (line.matches("cmdstat_(get|set|bf\\.exists|eval|del|publish):.*")) System.out.println("   " + line);
            System.out.println("   " + a.redis.configGet("maxmemory", "maxmemory-policy", "appendonly", "appendfsync", "io-threads"));
            for (String line : a.redis.info("persistence").split("\r?\n"))
                if (line.matches("(aof_enabled|aof_last_write_status|aof_current_size|rdb_last_save_time|rdb_changes_since_last_save):.*")) System.out.println("   " + line);
            for (String line : a.redis.info("server").split("\r?\n"))
                if (line.matches("(redis_version|multiplexing_api):.*")) System.out.println("   " + line);

            // 布隆过滤器的误判：拿 10 万个肯定不存在的 id 去问
            CommandArgs<String, String> q = new CommandArgs<>(StringCodec.UTF8).addKey("bf:product");
            for (int i = 1_000_001; i <= 1_100_000; i++) q.add(i);
            List<Boolean> res = a.redis.dispatch(new Kw("BF.MEXISTS"), new BooleanListOutput<>(StringCodec.UTF8), q);
            long fp = res.stream().filter(Boolean::booleanValue).count();
            System.out.println("   BF.MEXISTS 10 万个不存在的 id → 误判 " + fp + " 个");
        }
    }

    /** 第 1～8 站一站一站地走，每站计时。和 Instance.get 走的是同一条路，只是拆开了。 */
    static void traceOnce(Instance a, long id, boolean print) {
        String key = "product:" + id;
        List<String> out = new ArrayList<>();               // 先记下来最后再打印，打印本身不算进各站耗时
        long t0 = System.nanoTime(), t;
        String v = a.l1.getIfPresent(key);
        t = lap(out, "1  Caffeine getIfPresent", t0, "→ " + (v == null ? "miss" : "hit"));
        v = a.redis.get(key);
        t = lap(out, "2+3 GET " + key, t, "→ " + (v == null ? "nil" : "hit"));
        boolean e = bfExists(a.redis, id);
        t = lap(out, "4  BF.EXISTS bf:product " + id, t, "→ " + e);
        String token = UUID.randomUUID().toString();
        String ok = a.redis.set("lock:" + key, token, SetArgs.Builder.nx().px(3000));
        t = lap(out, "5  SET lock:" + key + " NX PX 3000", t, "→ " + ok);
        String json = a.selectProduct(id);
        t = lap(out, "6  SELECT … FROM product WHERE id=" + id, t, "→ " + json.getBytes(StandardCharsets.UTF_8).length + " 字节 JSON");
        a.redis.set(key, json, SetArgs.Builder.ex(1800 + ThreadLocalRandom.current().nextInt(300)));
        t = lap(out, "7a SET " + key + " EX 1800+随机", t, "");
        a.unlock("lock:" + key, token);
        t = lap(out, "7b EVAL 解锁（比较 token 再 DEL）", t, "");
        a.l1.put(key, json);
        t = lap(out, "8  Caffeine put", t, "");
        if (print) { for (String o : out) { String[] f = o.split("\\|", -1); System.out.printf("   %-38s %8s  %s%n", f[0], f[1], f[2]); } System.out.printf("   一共 %.0f µs%n", (t - t0) / 1e3); }
    }

    static long lap(List<String> out, String what, long since, String note) {
        long now = System.nanoTime();
        out.add(what + "|" + (now - since) / 1000 + " µs|" + note);
        return now;
    }

    /** 三条路径的耗时分布。 */
    static void bench() throws Exception {
        try (Instance a = new Instance("A", Duration.ofMinutes(5))) {
            String key = "product:" + SKU;
            a.get(SKU);
            for (int i = 0; i < 2000; i++) { a.l1.getIfPresent(key); a.redis.get(key); a.selectProduct(SKU); }
            int n = 5000;
            long[] l1 = new long[n], rd = new long[n], db = new long[n], miss = new long[300];
            for (int i = 0; i < n; i++) {
                long s = System.nanoTime(); a.l1.getIfPresent(key); l1[i] = System.nanoTime() - s;
                s = System.nanoTime(); a.redis.get(key); rd[i] = System.nanoTime() - s;
                s = System.nanoTime(); a.selectProduct(SKU); db[i] = System.nanoTime() - s;
            }
            // 全 miss 路径：每次换一个 id，本地和 Redis 里都没有
            for (int i = 0; i < miss.length; i++) {
                long id = 2000 + i;
                a.redis.del("product:" + id);
                long s = System.nanoTime(); a.get(id); miss[i] = System.nanoTime() - s;
            }
            System.out.println("== bench（各 " + n + " 次，全 miss 300 次）");
            pr("Caffeine 命中", l1); pr("Redis GET 命中", rd); pr("MySQL 主键 SELECT", db); pr("全 miss：GET+BF+锁+SELECT+SET+解锁", miss);
            String cs = a.redis.info("commandstats");
            Matcher m = Pattern.compile("cmdstat_get:calls=(\\d+),usec=(\\d+),usec_per_call=([\\d.]+)").matcher(cs);
            if (m.find()) System.out.println("   Redis 服务端执行 GET 平均 " + m.group(3) + " µs/次（INFO commandstats）");
        }
    }

    static void pr(String what, long[] ns) {
        long[] s = ns.clone(); Arrays.sort(s);
        System.out.printf("   %-34s p50 %9.2f µs   p99 %9.2f µs%n", what, s[s.length / 2] / 1e3, s[(int) (s.length * 0.99)] / 1e3);
    }

    /** 击穿：热门商品的缓存刚过期，4 个实例 × 50 个线程同时来查。 */
    static void hot() throws Exception {
        List<Instance> ins = new ArrayList<>();
        for (int i = 0; i < 4; i++) ins.add(new Instance("I" + i, Duration.ofMinutes(5)));
        try {
            for (Instance x : ins) for (int i = 0; i < 50; i++) x.selectProduct(3000 + i);  // 预热连接池
            System.out.println("== hot：88 号的缓存刚过期，4 个实例 × 50 个线程同时查（每种跑 5 轮）");
            for (String mode : List.of("裸查：本地 miss 就查 Redis，miss 就查库", "Caffeine 合并：同实例同 key 只放一个线程下去", "再加 Redis 重建锁：全集群只放一个线程下去")) {
                List<String> rounds = new ArrayList<>();
                for (int round = 0; round < 5; round++) {
                    ins.get(0).redis.del("product:" + SKU);
                    for (Instance x : ins) { x.l1.invalidateAll(); x.dbQueries.set(0); }
                    CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(200);
                    AtomicLong maxNs = new AtomicLong();
                    for (Instance x : ins) for (int i = 0; i < 50; i++) {
                        Thread.ofPlatform().start(() -> {
                            try {
                                go.await();
                                long s = System.nanoTime();
                                String key = "product:" + SKU;
                                if (mode.startsWith("裸查")) {
                                    String v = x.l1.getIfPresent(key);
                                    if (v == null) { v = x.loadFromRedisOrDb(SKU, false); x.l1.put(key, v); }
                                } else if (mode.startsWith("Caffeine")) {
                                    x.l1.get(key, k -> x.loadFromRedisOrDb(SKU, false));
                                } else {
                                    x.get(SKU);
                                }
                                maxNs.accumulateAndGet(System.nanoTime() - s, Math::max);
                            } catch (InterruptedException ignored) {
                            } finally { done.countDown(); }
                        });
                    }
                    go.countDown();
                    done.await();
                    int q = ins.stream().mapToInt(x -> x.dbQueries.get()).sum();
                    rounds.add(q + " 次（最慢 " + Math.round(maxNs.get() / 1e6) + " ms）");
                }
                System.out.println("   " + mode + "：查库 " + String.join(" / ", rounds));
            }
            String clients = ins.get(0).redis.info("clients");
            Matcher m = Pattern.compile("connected_clients:(\\d+)").matcher(clients);
            if (m.find()) System.out.println("   200 个线程同时在用时，Redis connected_clients = " + m.group(1) + "（4 个实例，每个一条连接）");
        } finally { for (Instance x : ins) x.close(); }
    }

    /** 改价：先改库，再删 Redis，再广播让各实例丢掉本地缓存。 */
    static void price() throws Exception {
        for (boolean pubsub : new boolean[]{true, false}) {
            try (Instance a = new Instance("A", Duration.ofSeconds(5)); Instance b = new Instance("B", Duration.ofSeconds(5));
                 Instance admin = new Instance("admin", Duration.ofSeconds(5))) {
                if (pubsub) { a.subscribe(); b.subscribe(); }
                setPrice(admin, "399.00");
                admin.redis.del("product:" + SKU);
                a.get(SKU); b.get(SKU);
                sleep(ThreadLocalRandom.current().nextInt(500, 3000));   // 线上本地缓存的“年龄”是随机的
                committedAt = 0;
                AtomicLong seenA = new AtomicLong(), seenB = new AtomicLong();
                AtomicInteger staleA = new AtomicInteger(), staleB = new AtomicInteger();
                Thread ra = reader(a, seenA, staleA), rb = reader(b, seenB, staleB);
                sleep(200);
                long t0 = System.nanoTime();
                setPrice(admin, "359.00");                                  // 第 9 站：改库并提交
                long tCommit = System.nanoTime();
                committedAt = tCommit;
                admin.redis.del("product:" + SKU);                           // 第 10 站：删 Redis
                long tDel = System.nanoTime();
                long tPub = tDel;
                if (pubsub) { admin.redis.publish(CHANNEL, "product:" + SKU); tPub = System.nanoTime(); }  // 第 11 站
                ra.join(15_000); rb.join(15_000);
                System.out.printf("== price（%s）：UPDATE 提交 %.1f ms，DEL %.2f ms%s%n", pubsub ? "有 Pub/Sub" : "没有 Pub/Sub，只靠本地 5 秒过期",
                        (tCommit - t0) / 1e6, (tDel - tCommit) / 1e6, pubsub ? String.format("，PUBLISH %.2f ms", (tPub - tDel) / 1e6) : "");
                System.out.printf("   实例 A 提交后 %.1f ms 读到 359.00（期间读到旧价 %d 次）%n", (seenA.get() - tCommit) / 1e6, staleA.get());
                System.out.printf("   实例 B 提交后 %.1f ms 读到 359.00（期间读到旧价 %d 次）%n", (seenB.get() - tCommit) / 1e6, staleB.get());
                if (pubsub) System.out.printf("   A 收到失效消息：提交后 %.2f ms；B：%.2f ms%n", (a.lastEvictNanos - tCommit) / 1e6, (b.lastEvictNanos - tCommit) / 1e6);
                setPrice(admin, "399.00");
            }
        }
    }

    static volatile long committedAt;

    static Thread reader(Instance x, AtomicLong seen, AtomicInteger stale) {
        return Thread.ofPlatform().start(() -> {
            long until = System.nanoTime() + 12_000_000_000L;
            while (System.nanoTime() < until) {
                String v = x.get(SKU);
                if (v.contains("\"359.00\"")) { seen.set(System.nanoTime()); return; }
                if (committedAt != 0) stale.incrementAndGet();          // 只数提交以后读到的旧价
                sleep(1);
            }
        });
    }

    static void setPrice(Instance admin, String p) throws Exception {
        try (Connection c = admin.db.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE product SET price = ? WHERE id = ?")) {
            ps.setBigDecimal(1, new java.math.BigDecimal(p));
            ps.setLong(2, SKU);
            ps.executeUpdate();
        }
    }

    static HikariDataSource ds() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(JDBC);
        hc.setMaximumPoolSize(2);
        return new HikariDataSource(hc);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
