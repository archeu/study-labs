package labs.redis.redis_oom_eviction;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

/**
 * 内存写满那一刻：交易靶场商品缓存在 maxmemory 2mb 下的三段实验。
 * <pre>
 *   oom     noeviction：回填 1 万个商品，看哪一站先报 OOM；20 个请求同时查一个没缓存的商品，库被查几次
 *   policy  noeviction / allkeys-lru / allkeys-lfu / volatile-lru 各回填一遍，看谁被赶走、热 key 88 和锁还在不在
 *   expire  全部带 2 秒 TTL 写满，到点以后多久能再写进去（主动过期在后台清）
 * </pre>
 * 参数：-Dport=63798（默认）-Dn=10000 -Dmax=2mb
 */
public class Lab {

    static final int PORT = Integer.getInteger("port", 63798);
    static final int N = Integer.getInteger("n", 10000);
    static final String MAX = System.getProperty("max", "2mb");

    static final AtomicInteger DB_HITS = new AtomicInteger();
    /** 数据库：每个商品一段 648 字节的 JSON，查一次 2 ms */
    static final LongFunction<String> DB = id -> {
        DB_HITS.incrementAndGet();
        ProductCache.sleep(2);
        return json(id);
    };

    public static void main(String[] args) throws Exception {
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + PORT);
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> r = conn.sync();
            System.out.println("== Redis " + info(r, "server", "redis_version") + "，maxmemory " + MAX + "，商品 " + N + " 个 ==");
            String which = args.length > 0 ? args[0] : "all";
            if (which.equals("all") || which.equals("oom")) oom(r);
            if (which.equals("all") || which.equals("policy")) policy(r);
            if (which.equals("all") || which.equals("expire")) expire(r);
            reset(r);
        } finally {
            client.shutdown();
        }
    }

    // ---------------------------------------------------------------- oom
    static void oom(RedisCommands<String, String> r) throws Exception {
        reset(r);
        System.out.println("\n[oom] 空库 used_memory " + kb(mem(r)) + "（还没存任何商品）");
        r.configSet("maxmemory-policy", "noeviction");
        r.configSet("maxmemory", MAX);
        ProductCache strict = new ProductCache(r, DB, false, 30 * 60_000L);
        int ok = 0;
        String firstErr = null;
        long firstFail = -1;
        for (long id = 1; id <= N; id++) {
            try {
                strict.get(id);
                ok++;
                if (firstErr != null) System.out.println("  失败之后又成功：商品 " + id);
            } catch (RedisCommandExecutionException e) {
                if (firstErr == null) {
                    firstErr = e.getMessage(); firstFail = id;
                    System.out.println("  第一次失败：商品 " + id + "，栈 " + e.getStackTrace()[0] + " ← " + where(e));
                }
            }
        }
        System.out.printf("  回填 %d 个：成功 %d，失败 %d；第一次失败在商品 %d%n", N, ok, N - ok, firstFail);
        System.out.println("  报错原文：" + firstErr);
        System.out.println("  此时 dbsize " + r.dbsize() + "，used_memory " + kb(mem(r)) + "，evicted_keys " + info(r, "stats", "evicted_keys"));

        System.out.println("  内存满时逐条试：");
        tryCmd("    GET product:1        ", () -> r.get("product:1") == null ? "nil" : "命中");
        tryCmd("    SET lock NX PX 3000  ", () -> r.set("lock:product:9999", "t", SetArgs.Builder.nx().px(3000)));
        tryCmd("    SET product:9999     ", () -> r.set("product:9999", json(9999)));
        tryCmd("    EXPIRE product:1 60  ", () -> String.valueOf(r.expire("product:1", 60)));
        tryCmd("    EVAL 解锁（GET+DEL） ", () -> String.valueOf((Object) r.eval(ProductCache.UNLOCK_LUA,
                io.lettuce.core.ScriptOutputType.INTEGER, new String[]{"lock:product:1"}, "t")));
        tryCmd("    DEL product:1        ", () -> String.valueOf(r.del("product:1")));
        tryCmd("    删一个以后 SET 能进吗", () -> r.set("product:9999", json(9999)));

        System.out.println("  20 个请求同时查没缓存的商品 9999：");
        rush(r, "    内存没满（maxmemory 0）     ", "0", false);
        rush(r, "    内存满了，不降级（抛异常）  ", MAX, false);
        rush(r, "    内存满了，降级（报错就查库）", MAX, true);
    }

    static void rush(RedisCommands<String, String> r, String label, String max, boolean fallback) throws Exception {
        r.del("product:9999", "lock:product:9999");
        r.configSet("maxmemory", max);
        ProductCache c = new ProductCache(r, DB, fallback, 30 * 60_000L);
        DB_HITS.set(0);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) fs.add(pool.submit(() -> { go.await(); return c.get(9999); }));
        go.countDown();
        int ok = 0, err = 0;
        for (Future<String> f : fs) {
            try { if (f.get() != null) ok++; } catch (Exception e) { err++; }
        }
        pool.shutdown();
        System.out.printf("%s 拿到详情 %2d，报错 %2d，查库 %2d 次%n", label, ok, err, DB_HITS.get());
    }

    // ---------------------------------------------------------------- policy
    static void policy(RedisCommands<String, String> r) {
        System.out.println("\n[policy] 先把 88 号读热（200 次），拿着 lock:product:20001（回填范围外，PX 600000），再回填 1～" + N + "，每回填 10 个读一次 88 号");
        System.out.println("  策略            OOM次数  淘汰数  剩下  88号  锁20001  1～1000剩  后1000剩  耗时");
        for (String p : new String[]{"noeviction", "allkeys-lru", "allkeys-lfu", "volatile-lru"}) {
            reset(r);
            r.configSet("maxmemory-policy", p);
            ProductCache c = new ProductCache(r, DB, true, 30 * 60_000L);
            c.get(88);
            for (int i = 0; i < 200; i++) r.get("product:88");
            r.set("lock:product:20001", "t", SetArgs.Builder.px(600_000));
            r.configSet("maxmemory", MAX);
            long t0 = System.currentTimeMillis();
            for (long id = 1; id <= N; id++) {
                if (id == 88) continue;
                c.get(id);
                if (id % 10 == 0) c.get(88);
            }
            double secs = (System.currentTimeMillis() - t0) / 1000.0;
            r.configSet("maxmemory", "0");   // 先停住：内存超线时，连 EXISTS 之前都会先淘汰一轮，边数边少
            int head = 0, tail = 0;
            for (long id = 1; id <= 1000; id++) if (id != 88 && r.exists("product:" + id) == 1) head++;
            for (long id = N - 999; id <= N; id++) if (r.exists("product:" + id) == 1) tail++;
            System.out.printf("  %-14s %7s %7s %5d  %-4s  %-7s  %8d  %8d  %4.1f s%n", p,
                    errCount(r, "OOM"), info(r, "stats", "evicted_keys"), r.dbsize(),
                    r.exists("product:88") == 1 ? "在" : "没了", r.exists("lock:product:20001") == 1 ? "在" : "没了",
                    head, tail, secs);
        }
    }

    // ---------------------------------------------------------------- expire
    static void expire(RedisCommands<String, String> r) throws Exception {
        reset(r);
        r.configSet("maxmemory-policy", "noeviction");
        r.configSet("maxmemory", MAX);
        int ttl = 2000, n = 0;
        long t0 = System.currentTimeMillis();
        try {
            for (long id = 1; ; id++) { r.set("product:" + id, json(id), SetArgs.Builder.px(ttl)); n++; }
        } catch (RedisCommandExecutionException e) {
            // 写满了
        }
        long filled = System.currentTimeMillis() - t0;
        System.out.printf("%n[expire] noeviction，%d 个商品全带 PX %d 写满（用了 %d ms），然后每 100 ms 看一次%n", n, ttl, filled);
        System.out.println("  距第一次写入   dbsize  used_memory  expired_keys  SET 新商品");
        long until = t0 + ttl + 2500;
        while (System.currentTimeMillis() < until) {
            long now = System.currentTimeMillis() - t0;
            String probe;
            try { r.set("probe", "x"); r.del("probe"); probe = "OK"; }
            catch (RedisCommandExecutionException e) { probe = "OOM"; }
            if (now > ttl - 300) {
                System.out.printf("  %6d ms   %7d  %11s  %12s  %s%n", now, r.dbsize(), kb(mem(r)),
                        info(r, "stats", "expired_keys"), probe);
            }
            ProductCache.sleep(100);
        }
    }

    // ---------------------------------------------------------------- helpers
    static void reset(RedisCommands<String, String> r) {
        r.configSet("maxmemory", "0");
        r.flushall();
        r.configResetstat();
        ProductCache.sleep(200);   // 等 flushall 后内存回落
    }

    static String where(Exception e) {
        for (StackTraceElement el : e.getStackTrace())
            if (el.getClassName().endsWith("ProductCache")) return "ProductCache.java:" + el.getLineNumber();
        return "?";
    }

    interface Cmd { String run(); }

    static void tryCmd(String label, Cmd c) {
        try { System.out.println(label + " → " + c.run()); }
        catch (RedisCommandExecutionException e) { System.out.println(label + " → " + e.getMessage()); }
    }

    static long mem(RedisCommands<String, String> r) { return Long.parseLong(info(r, "memory", "used_memory")); }

    static String kb(long b) { return String.format("%.1f KB", b / 1024.0); }

    static String info(RedisCommands<String, String> r, String section, String field) {
        for (String line : r.info(section).split("\r\n")) {
            if (line.startsWith(field + ":")) return line.substring(field.length() + 1);
        }
        return "?";
    }

    static String errCount(RedisCommands<String, String> r, String err) {
        String s = info(r, "errorstats", "errorstat_" + err);
        return s.equals("?") ? "0" : s.substring(s.indexOf('=') + 1);
    }

    /** 88 号那段详情：648 字节的 JSON，id 不同内容略不同 */
    static String json(long id) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(id).append(",\"name\":\"机械键盘 K").append(id)
                .append("\",\"price\":\"399.00\",\"stock\":1200,\"detail\":\"");
        while (sb.length() < 640) sb.append("d");
        return sb.append("\"}").toString();
    }
}
