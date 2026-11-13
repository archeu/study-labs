package labs.redis.cache_five_holes;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

/**
 * 五段实验，对应页面：
 *   pen    ⑤ 穿透：随机不存在的 id，裸查 / 缓存空值 / 布隆过滤器
 *   hot    ⑤ 击穿：88 号刚过期，4 个实例 × 50 个线程同时查；裸查 / 重建锁 / 逻辑过期（⑥）
 *   storm  ⑤ 雪崩：2000 个 key 同一时刻预热、同样的过期时间，对比加随机数
 *   big    ⑥ 大 key：88 号的 JSON 从 648 B 变成 2 MB（-DbigKb=200 换成 200 KB），共用一条连接的其他请求慢了多少；DEL 和 UNLINK 一个百万字段的 Hash
 *   hotkeys ⑥ 找热 key、大 key：Redis 8.6 起的 HOTKEYS 命令
 *   info   ⑥ 版本差异：几个默认配置
 */
public class Lab {
    static final String URI = System.getProperty("redis", "redis://127.0.0.1:63799");
    static final int DB_MILLIS = 2;                       // 假装一次主键查询 2 ms（库有压力时的样子）
    static final AtomicInteger dbHits = new AtomicInteger();
    static final ThreadLocal<int[]> myDbHits = ThreadLocal.withInitial(() -> new int[1]);
    static String json88;

    /** 交易靶场的 product 表：id 1～10000 有货，其余不存在 */
    static final LongFunction<String> DB = id -> {
        dbHits.incrementAndGet();
        myDbHits.get()[0]++;
        ProductCache.sleep(DB_MILLIS);
        if (id < 1 || id > 10_000) return null;
        return id == 88 ? json88 : "{\"id\":" + id + ",\"price\":\"99.00\"}";
    };

    public static void main(String[] args) throws Exception {
        json88 = product88(648);
        RedisClient client = RedisClient.create(RedisURI.create(URI));
        List<String> parts = args.length > 0 ? Arrays.asList(args) : Arrays.asList("pen", "hot", "storm", "big", "hotkeys", "info");
        try {
            for (String p : parts) {
                switch (p) {
                    case "pen": pen(client); break;
                    case "hot": hot(client); break;
                    case "storm": storm(client); break;
                    case "big": big(client); break;
                    case "info": info(client); break;
                    case "hotkeys": hotkeys(client); break;
                    default: System.out.println("不认识的段：" + p);
                }
            }
        } finally {
            client.shutdown();
        }
    }

    // ------------------------------------------------------------------ 穿透
    static void pen(RedisClient client) throws Exception {
        System.out.println("== pen：每轮 10000 个随机的、不存在的商品 id，8 个线程查");
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            RedisCommands<String, String> r = c.sync();
            long[] ids = new long[10_000];
            for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
            String[] names = {"裸查", "缓存空值", "布隆过滤器"};
            ProductCache.Options[] opts = {ProductCache.Options.none(),
                    ProductCache.Options.none().cacheNull(true), ProductCache.Options.none().bloom(true)};
            for (int k = 0; k < opts.length; k++) {
                r.flushdb();
                ProductCache.bloomInit(r, 100_000, 0.001, ids);       // 1 万个在售，按 10 万容量、0.1% 误判率建
                long mem0 = usedMemory(r);
                ProductCache pc = new ProductCache(r, DB, opts[k]);
                long[] attack = randomIds(10_000, 1);
                int a = run(pc, attack), b = run(pc, attack), cc = run(pc, randomIds(10_000, 2));
                System.out.printf("   %-6s  同一批 id：第 1 轮查库 %5d，第 2 轮 %5d；换一批新 id：%5d   Redis 多了 %5d 个 key、%,d 字节%n",
                        names[k], a, b, cc, r.dbsize() - 1, usedMemory(r) - mem0);
            }
        }
    }

    static long[] randomIds(int n, long seed) {
        Random rnd = new Random(seed);
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = 100_000_000L + (rnd.nextLong() & Long.MAX_VALUE) % 900_000_000L;
        return a;
    }

    static int run(ProductCache pc, long[] ids) throws Exception {
        dbHits.set(0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int start = t;
            fs.add(pool.submit(() -> { for (int i = start; i < ids.length; i += 8) pc.get(ids[i]); }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        return dbHits.get();
    }

    static long usedMemory(RedisCommands<String, String> r) {
        for (String line : r.info("memory").split("\r\n"))
            if (line.startsWith("used_memory:")) return Long.parseLong(line.substring(12));
        return -1;
    }

    // ------------------------------------------------------------------ 击穿
    static void hot(RedisClient client) throws Exception {
        System.out.println("== hot：88 号刚过期，4 个实例（各一条连接）× 50 个线程同时查，10 轮");
        List<StatefulRedisConnection<String, String>> conns = new ArrayList<>();
        for (int i = 0; i < 4; i++) conns.add(client.connect());
        RedisCommands<String, String> admin = conns.get(0).sync();
        String[] names = {"裸查", "重建锁"};
        ProductCache.Options[] opts = {ProductCache.Options.none(), ProductCache.Options.none().lock(true)};
        ExecutorService pool = Executors.newFixedThreadPool(200);
        {   // 对照：88 号好好地在缓存里，200 个线程一起读（本机 2 核，这就是排队本身的耗时）
            List<ProductCache> inst = new ArrayList<>();
            for (StatefulRedisConnection<String, String> c : conns) inst.add(new ProductCache(c.sync(), DB, ProductCache.Options.none()));
            Stats s = new Stats();
            for (int round = 0; round < 10; round++) {
                admin.set("product:88", json88);
                dbHits.set(0);
                s.add(dbHits.get(), burst(pool, i -> inst.get(i % 4).get(88)), 0);
            }
            s.print("没过期");
        }
        for (int k = 0; k < 2; k++) {
            List<ProductCache> inst = new ArrayList<>();
            for (StatefulRedisConnection<String, String> c : conns) inst.add(new ProductCache(c.sync(), DB, opts[k]));
            Stats s = new Stats();
            for (int round = 0; round < 10; round++) {
                admin.del("product:88", "lock:product:88");
                dbHits.set(0);
                long[] lat = burst(pool, i -> inst.get(i % 4).get(88));
                s.add(dbHits.get(), lat, 0);
            }
            s.print(names[k]);
        }
        // 逻辑过期：值一直在，只是带的时间戳过期了
        ExecutorService rebuilder = Executors.newSingleThreadExecutor();
        Stats s = new Stats();
        for (int round = 0; round < 10; round++) {
            admin.set("product:88", (System.currentTimeMillis() - 1) + "|" + json88);
            admin.del("lock:product:88");
            dbHits.set(0);
            int[] stale = {0};
            long[] lat = burst(pool, i -> ProductCache.getLogical(conns.get(i % 4).sync(), 88, DB, 30 * 60_000L, rebuilder, stale));
            Thread.sleep(50);                                          // 等后台重建完
            s.add(dbHits.get(), lat, stale[0]);
        }
        s.print("逻辑过期");
        rebuilder.shutdown();
        pool.shutdown();
        for (StatefulRedisConnection<String, String> c : conns) c.close();
    }

    interface Call { void call(int i) throws Exception; }

    /** 200 个线程在同一时刻放出去，各自查一次，返回每个请求的耗时（微秒） */
    static long[] burst(ExecutorService pool, Call call) throws Exception {
        int n = 200;
        CyclicBarrier go = new CyclicBarrier(n);
        long[] lat = new long[n];
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            fs.add(pool.submit(() -> {
                go.await();
                long t0 = System.nanoTime();
                call.call(idx);
                lat[idx] = (System.nanoTime() - t0) / 1000;
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        return lat;
    }

    static class Stats {
        int hitMin = Integer.MAX_VALUE, hitMax, staleMin = Integer.MAX_VALUE, staleMax;
        List<Long> all = new ArrayList<>(), maxes = new ArrayList<>();
        void add(int hits, long[] lat, int stale) {
            hitMin = Math.min(hitMin, hits); hitMax = Math.max(hitMax, hits);
            staleMin = Math.min(staleMin, stale); staleMax = Math.max(staleMax, stale);
            long m = 0;
            for (long l : lat) { all.add(l); m = Math.max(m, l); }
            maxes.add(m);
        }
        void print(String name) {
            Collections.sort(all); Collections.sort(maxes);
            System.out.printf("   %-6s 每轮查库 %3d～%3d 次  p50 %6.1f ms  每轮最慢 %5.1f～%5.1f ms  读到旧值 %d～%d 次%n",
                    name, hitMin, hitMax, all.get(all.size() / 2) / 1000.0,
                    maxes.get(0) / 1000.0, maxes.get(maxes.size() - 1) / 1000.0, staleMin, staleMax);
        }
    }

    // ------------------------------------------------------------------ 雪崩
    static void storm(RedisClient client) throws Exception {
        System.out.println("== storm：2000 个商品同一时刻预热，过期 3 秒；8 个线程随机读 9 秒，每 250 ms 数一次查库");
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            RedisCommands<String, String> r = c.sync();
            String[] names = {"固定 3 秒", "3 秒 + 0～3 秒随机"};
            ProductCache.Options[] opts = {ProductCache.Options.none().ttl(3000, 0),
                    ProductCache.Options.none().jitter(true).ttl(3000, 3000)};
            for (int k = 0; k < 2; k++) {
                r.flushdb();
                ProductCache pc = new ProductCache(r, DB, opts[k]);
                long[] ids = new long[2000];
                for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
                long w0 = System.currentTimeMillis();
                run(pc, ids);                                          // 预热
                long warm = System.currentTimeMillis() - w0;
                int buckets = 36;
                AtomicInteger[] perBucket = new AtomicInteger[buckets];
                for (int i = 0; i < buckets; i++) perBucket[i] = new AtomicInteger();
                dbHits.set(0);
                long t0 = System.currentTimeMillis();
                ExecutorService pool = Executors.newFixedThreadPool(8);
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < 8; t++) fs.add(pool.submit(() -> {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    while (true) {
                        long el = System.currentTimeMillis() - t0;
                        if (el >= buckets * 250L) return;
                        int[] mine = myDbHits.get();
                        int before = mine[0];
                        pc.get(1 + rnd.nextInt(2000));
                        if (mine[0] != before) perBucket[(int) Math.min(buckets - 1, el / 250)].incrementAndGet();
                    }
                }));
                for (Future<?> f : fs) f.get();
                pool.shutdown();
                int peak = 0, total = 0;
                StringBuilder bar = new StringBuilder();
                String blocks = "▁▂▃▄▅▆▇█";
                for (AtomicInteger b : perBucket) { peak = Math.max(peak, b.get()); total += b.get(); }
                for (AtomicInteger b : perBucket)
                    bar.append(b.get() == 0 ? '·' : blocks.charAt(Math.min(7, b.get() * 8 / Math.max(1, peak + 1))));
                System.out.printf("   %-14s 预热 %4d ms；9 秒里查库 %4d 次，最挤的 250 ms 里 %3d 次%n      %s%n",
                        names[k], warm, total, peak, bar);
                if (Boolean.getBoolean("raw")) {
                    StringBuilder raw = new StringBuilder("      ");
                    for (AtomicInteger b : perBucket) raw.append(b.get()).append(' ');
                    System.out.println(raw);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 大 key
    static void big(RedisClient client) throws Exception {
        System.out.println("== big：16 个线程在一条共用连接上读 648 B 的 88 号，3 秒；旁边有人读 " + Integer.getInteger("bigKb", 2048) + " KB 的大 key");
        try (StatefulRedisConnection<String, String> shared = client.connect();
             StatefulRedisConnection<String, String> other = client.connect()) {
            RedisCommands<String, String> r = shared.sync();
            r.flushdb();
            r.set("product:88", json88);
            r.set("product:88:big", product88(Integer.getInteger("bigKb", 2048) * 1024));
            System.out.printf("   MEMORY USAGE：小 %d B，大 %d B；编码 %s / %s%n",
                    r.memoryUsage("product:88"), r.memoryUsage("product:88:big"),
                    r.objectEncoding("product:88"), r.objectEncoding("product:88:big"));
            r.configResetstat();
            bigRun("没有大 key", shared, null, 0);
            bigRun("1 个线程读大 key，同一条连接", shared, shared, 1);
            bigRun("4 个线程读大 key，同一条连接", shared, shared, 4);
            bigRun("4 个线程读大 key，另一条连接", shared, other, 4);
            for (String line : r.info("commandstats").split("\r\n"))
                if (line.startsWith("cmdstat_get:")) System.out.println("   服务端 " + line);

            // 删一个百万字段的 Hash：DEL 在主线程里一个个释放，UNLINK 交给后台线程
            System.out.println("   lazyfree-lazy-user-del = " + r.configGet("lazyfree-lazy-user-del"));
            for (String cmd : new String[]{"DEL", "UNLINK"}) {
                fillHash(shared, "cart:big", 1_000_000);
                long t0 = System.nanoTime();
                if (cmd.equals("DEL")) r.del("cart:big"); else r.unlink("cart:big");
                System.out.printf("   %-6s 一个 100 万字段的 Hash：%.1f ms%n", cmd, (System.nanoTime() - t0) / 1e6);
            }
            String orig = r.configGet("lazyfree-lazy-user-del").get("lazyfree-lazy-user-del");
            r.configSet("lazyfree-lazy-user-del", "yes");             // 打开后，DEL 也和 UNLINK 一样交给后台
            fillHash(shared, "cart:big", 1_000_000);
            long t0 = System.nanoTime();
            r.del("cart:big");
            System.out.printf("   DEL（lazyfree-lazy-user-del 改成 yes）：%.1f ms%n", (System.nanoTime() - t0) / 1e6);
            r.configSet("lazyfree-lazy-user-del", orig);
        }
    }

    static void fillHash(StatefulRedisConnection<String, String> c, String key, int n) {
        RedisAsyncCommands<String, String> a = c.async();
        List<RedisFuture<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i += 1000) {
            Map<String, String> m = new HashMap<>();
            for (int j = i; j < i + 1000; j++) m.put("sku:" + j, "2");
            fs.add(a.hset(key, m));
            if (fs.size() == 100) { LettuceFutures.awaitAll(30, TimeUnit.SECONDS, fs.toArray(new RedisFuture[0])); fs.clear(); }
        }
        LettuceFutures.awaitAll(30, TimeUnit.SECONDS, fs.toArray(new RedisFuture[0]));
    }

    static void bigRun(String name, StatefulRedisConnection<String, String> small,
                       StatefulRedisConnection<String, String> big, int bigThreads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16 + bigThreads);
        long end = System.currentTimeMillis() + 3000;
        List<Future<List<Long>>> fs = new ArrayList<>();
        for (int t = 0; t < 16; t++) fs.add(pool.submit(() -> {
            List<Long> l = new ArrayList<>();
            RedisCommands<String, String> r = small.sync();
            while (System.currentTimeMillis() < end) {
                long t0 = System.nanoTime();
                r.get("product:88");
                l.add((System.nanoTime() - t0) / 1000);
            }
            return l;
        }));
        AtomicInteger bigOps = new AtomicInteger();
        for (int t = 0; t < bigThreads; t++) pool.submit(() -> {
            RedisCommands<String, String> r = big.sync();
            while (System.currentTimeMillis() < end) { r.get("product:88:big"); bigOps.incrementAndGet(); }
        });
        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> f : fs) all.addAll(f.get());
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        Collections.sort(all);
        System.out.printf("   %-26s 小 GET %6d 次  p50 %6.2f ms  p99 %6.2f ms   大 GET %5d 次%n", name, all.size(),
                all.get(all.size() / 2) / 1000.0, all.get(all.size() * 99 / 100) / 1000.0, bigOps.get());
    }

    // ------------------------------------------------------------------ 找热 key、大 key
    static void hotkeys(RedisClient client) throws Exception {
        System.out.println("== hotkeys：2000 个商品，8 个线程读 3 秒，一半的读落在 88 号上；另有 1 个线程读 200 KB 的 product:7:big");
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            RedisCommands<String, String> r = c.sync();
            r.flushdb();
            for (int i = 1; i <= 2000; i++) r.set("product:" + i, i == 88 ? json88 : "{\"id\":" + i + ",\"price\":\"99.00\"}");
            r.set("product:7:big", product88(200 * 1024));
            // Redis 8.6 起：服务端自己数，按 CPU 时间和网络字节各排一个前 K 名
            System.out.println("   " + r.dispatch(Raw.HOTKEYS, new io.lettuce.core.output.StatusOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                    new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8)
                            .add("START").add("METRICS").add(2).add("CPU").add("NET").add("COUNT").add(5)));
            long end = System.currentTimeMillis() + 3000;
            ExecutorService pool = Executors.newFixedThreadPool(9);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) fs.add(pool.submit(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (System.currentTimeMillis() < end) r.get("product:" + (rnd.nextBoolean() ? 88 : 1 + rnd.nextInt(2000)));
            }));
            fs.add(pool.submit(() -> { while (System.currentTimeMillis() < end) { r.get("product:7:big"); ProductCache.sleep(5); } }));
            for (Future<?> f : fs) f.get();
            pool.shutdown();
            List<Object> res = r.dispatch(Raw.HOTKEYS, new io.lettuce.core.output.NestedMultiOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                    new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8).add("GET"));
            System.out.println("   HOTKEYS GET → " + res);
            r.dispatch(Raw.HOTKEYS, new io.lettuce.core.output.StatusOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                    new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8).add("STOP"));
        }
    }

    enum Raw implements io.lettuce.core.protocol.ProtocolKeyword {
        HOTKEYS;
        @Override public byte[] getBytes() { return name().getBytes(java.nio.charset.StandardCharsets.US_ASCII); }
    }

    // ------------------------------------------------------------------ 版本差异
    static void info(RedisClient client) {
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            RedisCommands<String, String> r = c.sync();
            System.out.println("== info");
            for (String line : r.info("server").split("\r\n"))
                if (line.startsWith("redis_version")) System.out.println("   " + line);
            for (String k : new String[]{"maxmemory-policy", "lazyfree-lazy-user-del", "lazyfree-lazy-user-flush",
                    "lazyfree-lazy-expire", "lazyfree-lazy-eviction", "lazyfree-lazy-server-del", "hz", "lfu-log-factor"})
                System.out.println("   " + r.configGet(k));
        }
    }

    /** 88 号的 JSON，detail 字段补到 size 字节 */
    static String product88(int size) {
        String head = "{\"id\":88,\"name\":\"降噪耳机 Pro\",\"price\":\"399.00\",\"stock\":1200,\"detail\":\"";
        StringBuilder sb = new StringBuilder(head);
        int pad = size - 2 - head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (int i = 0; i < pad; i++) sb.append('x');
        return sb.append("\"}").toString();
    }
}
