package labs.redis.cache_multilevel;

import com.github.benmanes.caffeine.cache.Cache;
import io.lettuce.core.KillArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 三段：
 *   quick  两个实例 A、B 读 88 号、改价、再读：看每次是在哪一层拿到的
 *   gap    订阅连接断了的那一会儿改价：失效消息丢了，A（不清本地）和 B（重连后清本地）多久读到新价
 *   race   失效消息正好撞上本实例“正在从 Redis 加载 88 号”：getIfPresent+put 和 get(key, loader) 谁会留下旧价
 * Redis 地址默认 redis://127.0.0.1:63798，可用 -Dredis=... 改。
 */
public class Lab {
    static final String KEY = "product:88";
    static final String OLD = "{\"id\":88,\"name\":\"保温杯\",\"price\":\"399.00\"}";
    static final String NEW = "{\"id\":88,\"name\":\"保温杯\",\"price\":\"359.00\"}";

    /** 数据库用一个 Map 代替：每次查 1 ms。这页只看两级缓存，库的部分见全景第 6 站 */
    static final Map<String, String> TABLE = new ConcurrentHashMap<>();
    static final Function<String, String> DB = k -> { sleep(1); return TABLE.getOrDefault(k, "{\"id\":0,\"price\":\"0.00\"}"); };

    static final java.util.logging.Logger QUIET = java.util.logging.Logger.getLogger("io.lettuce");

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        QUIET.setLevel(java.util.logging.Level.WARNING);           // 关掉 Lettuce 重连时的 INFO 日志
        RedisClient client = RedisClient.create(System.getProperty("redis", "redis://127.0.0.1:63798"));
        try (StatefulRedisConnection<String, String> ac = client.connect()) {
            RedisCommands<String, String> admin = ac.sync();
            if (mode.equals("all") || mode.equals("quick")) quick(client, admin);
            if (mode.equals("all") || mode.equals("gap")) gap(client, admin);
            if (mode.equals("all") || mode.equals("race")) race(client, admin);
        } finally {
            client.shutdown();
        }
    }

    // ---------------------------------------------------------------- quick
    static void quick(RedisClient client, RedisCommands<String, String> admin) {
        System.out.println("== quick：两个实例，本地缓存 5 秒，Redis 30 分钟起");
        reset(admin);
        try (TwoLevelCache a = new TwoLevelCache("A", client, DB, 5000, true);
             TwoLevelCache b = new TwoLevelCache("B", client, DB, 5000, true)) {
            for (int r = 0; r < 3; r++) for (int i = 0; i < 200; i++) { a.get("warm:" + i); b.get("warm:" + i); }  // 先热身（三遍，后两遍走本地命中），类加载和 JIT 不算进下面
            read(a, "A 第一次查");
            read(a, "A 再查一次");
            read(b, "B 第一次查");
            TABLE.put(KEY, NEW);                                   // 第 9 站：改库
            long t0 = System.nanoTime();
            long receivers = TwoLevelCache.evict(admin, KEY);      // 第 10、11 站
            System.out.printf("   改价：UPDATE 后 DEL + PUBLISH，%d µs，%d 个实例收到消息%n", (System.nanoTime() - t0) / 1000, receivers);
            sleep(20);
            read(a, "A 改价后查");
            read(b, "B 改价后查");
            read(b, "B 再查一次");
        }
    }

    static void read(TwoLevelCache c, String label) {
        long t0 = System.nanoTime();
        String v = c.get(KEY);
        long us = (System.nanoTime() - t0) / 1000;
        System.out.printf("   %-10s %-5s %6d µs  price=%s%n", label, TwoLevelCache.SOURCE.get(), us, price(v));
    }

    // ---------------------------------------------------------------- gap
    static void gap(RedisClient client, RedisCommands<String, String> admin) {
        System.out.println("== gap：订阅连接被断开，紧接着改价（本地缓存 5 秒）");
        for (int round = 1; round <= 3; round++) {
            reset(admin);
            try (TwoLevelCache a = new TwoLevelCache("A", client, DB, 5000, false);
                 TwoLevelCache b = new TwoLevelCache("B", client, DB, 5000, true)) {
                a.get(KEY); b.get(KEY);                             // 两个实例本地都有 399
                sleep(50);
                long killed = admin.clientKill(KillArgs.Builder.typePubsub());
                TABLE.put(KEY, NEW);
                long receivers = TwoLevelCache.evict(admin, KEY);
                long t0 = System.nanoTime();
                long aNew = -1, bNew = -1; int aStale = 0, bStale = 0;
                while ((aNew < 0 || bNew < 0) && System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(8)) {
                    long now = (System.nanoTime() - t0) / 1_000_000;
                    if (aNew < 0) { if (a.get(KEY).equals(NEW)) aNew = now; else aStale++; }
                    if (bNew < 0) { if (b.get(KEY).equals(NEW)) bNew = now; else bStale++; }
                    sleep(1);
                }
                System.out.printf("   第 %d 轮：断开 %d 条订阅连接；PUBLISH 收到的实例 = %d%n", round, killed, receivers);
                System.out.printf("      A（只靠本地过期）  %5d ms 后读到 359.00，期间读到旧价 %4d 次%n", aNew, aStale);
                System.out.printf("      B（断线、补订都清）%5d ms 后读到 359.00，期间读到旧价 %4d 次%n", bNew, bStale);
                System.out.printf("      订阅确认：A %d 次，B %d 次（第 2 次就是重连后自动补订）%n", a.subscribes.get(), b.subscribes.get());
                System.out.printf("      之后再 PUBLISH 一次：%d 个实例收到（已自动重新订阅）%n", admin.publish(TwoLevelCache.CHANNEL, "noop"));
            }
        }
    }

    // ---------------------------------------------------------------- race
    static void race(RedisClient client, RedisCommands<String, String> admin) throws Exception {
        System.out.println("== race：本实例正从 Redis 读 88 号（读到旧价后卡 200 ms），这时改价、失效消息到了");
        for (String variant : new String[]{"getIfPresent+put", "get(key, loader)"}) {
            for (int round = 1; round <= 2; round++) {
                reset(admin);
                admin.set(KEY, OLD);                                // Redis 里是旧价，本地是空的
                try (TwoLevelCache c = new TwoLevelCache("A", client, DB, 5000, true)) {
                    CountDownLatch readOld = new CountDownLatch(1);
                    Function<String, String> slowLoad = k -> {
                        String v = c.redis.get(k);                  // 读到 399
                        readOld.countDown();
                        sleep(200);                                 // 这时线程卡住：GC、调度、网络抖动
                        return v;
                    };
                    Cache<String, String> l1 = c.l1;
                    Thread reader = new Thread(() -> {
                        if (variant.startsWith("getIfPresent")) {
                            String v = l1.getIfPresent(KEY);
                            if (v == null) { v = slowLoad.apply(KEY); l1.put(KEY, v); }
                        } else {
                            l1.get(KEY, slowLoad);
                        }
                    });
                    reader.start();
                    readOld.await();
                    TABLE.put(KEY, NEW);
                    long t0 = System.nanoTime();
                    long receivers = TwoLevelCache.evict(admin, KEY);
                    long evictMs = (System.nanoTime() - t0) / 1_000_000;
                    reader.join();
                    sleep(20);                                      // 等监听器干完
                    long t1 = System.nanoTime();
                    int stale = 0;
                    while (!c.get(KEY).equals(NEW) && System.nanoTime() - t1 < TimeUnit.SECONDS.toNanos(8)) { stale++; sleep(1); }
                    long staleMs = (System.nanoTime() - t1) / 1_000_000;
                    System.out.printf("   %-17s 第 %d 轮：DEL+PUBLISH %3d ms（%d 个实例收到），监听器里 invalidate 用了 %6.1f ms；加载结束后旧价又留了 %4d ms（读到 %d 次）%n",
                            variant, round, evictMs, receivers, c.lastInvalidateNanos / 1e6, staleMs, stale);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具
    static void reset(RedisCommands<String, String> admin) {
        admin.del(KEY);
        TABLE.put(KEY, OLD);
    }

    static String price(String json) {
        int i = json.indexOf("\"price\":\"");
        return json.substring(i + 9, json.indexOf('"', i + 9));
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
