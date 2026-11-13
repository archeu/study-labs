package labs.redis.cache_five_holes;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.BooleanListOutput;
import io.lettuce.core.output.BooleanOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongFunction;

/**
 * 交易靶场商品服务读详情：全景“一次商品详情查询”第 3～7 站，带三道防线。
 * <ul>
 *   <li>第 4 站 布隆过滤器：挡穿透（查根本不存在的商品）</li>
 *   <li>第 5 站 重建锁 SET NX PX + Lua 解锁：挡击穿（热门 key 过期那一刻）</li>
 *   <li>第 7 站 过期时间加随机数：挡雪崩（一批 key 同一秒过期）</li>
 * </ul>
 * 每道防线都能单独关掉，Lab 用它对比“有它”和“没它”。
 */
public class ProductCache {

    /** 每道防线的开关 */
    public static class Options {
        boolean bloom, cacheNull, lock, jitter;
        long ttlMillis = 30 * 60_000L;           // 30 分钟
        long jitterMillis = 5 * 60_000L;         // 再加 0～5 分钟

        public static Options none() { return new Options(); }
        public static Options all() { Options o = new Options(); o.bloom = o.lock = o.jitter = true; return o; }
        public Options bloom(boolean b) { bloom = b; return this; }
        public Options cacheNull(boolean b) { cacheNull = b; return this; }
        public Options lock(boolean b) { lock = b; return this; }
        public Options jitter(boolean b) { jitter = b; return this; }
        public Options ttl(long base, long jitter) { ttlMillis = base; jitterMillis = jitter; return this; }
    }

    static final String NULL_MARK = "";           // 缓存空值时存的占位
    static final String BLOOM_KEY = "bf:product";
    static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    final RedisCommands<String, String> redis;    // 一个实例一条连接，所有线程共用（Lettuce 的默认用法）
    final LongFunction<String> db;                // 数据库：按 id 查，查不到返回 null
    final Options opt;

    public ProductCache(RedisCommands<String, String> redis, LongFunction<String> db, Options opt) {
        this.redis = redis;
        this.db = db;
        this.opt = opt;
    }

    public String get(long id) {
        String key = "product:" + id;
        String v = redis.get(key);                                   // 第 2、3 站
        if (v != null) return NULL_MARK.equals(v) ? null : v;
        if (opt.bloom && !bloomMightContain(id)) return null;        // 第 4 站：一定没有，直接 404
        if (!opt.lock) return loadAndFill(id, key);                  // 没有锁：谁没命中谁查库

        String lockKey = "lock:" + key, token = UUID.randomUUID().toString();
        while (true) {
            // 第 5 站：key 不存在才设得上，3 秒后自动过期（拿锁的实例挂了，锁也会自己放掉）
            if ("OK".equals(redis.set(lockKey, token, SetArgs.Builder.nx().px(3000)))) {
                try {
                    v = redis.get(key);                              // 再看一眼：可能上一个拿锁的刚建好
                    if (v != null) return NULL_MARK.equals(v) ? null : v;
                    return loadAndFill(id, key);                     // 第 6、7 站
                } finally {                                          // 第 7 站：是自己的锁才删
                    redis.eval(UNLOCK_LUA, ScriptOutputType.INTEGER, new String[]{lockKey}, token);
                }
            }
            sleep(10);                                               // 没抢到：等 10 ms 回头看缓存
            v = redis.get(key);
            if (v != null) return NULL_MARK.equals(v) ? null : v;
        }
    }

    String loadAndFill(long id, String key) {
        String v = db.apply(id);                                     // 第 6 站
        if (v == null) {
            if (opt.cacheNull) redis.set(key, NULL_MARK, SetArgs.Builder.px(60_000));  // 空值只存 60 秒
            return null;
        }
        long ttl = opt.ttlMillis + (opt.jitter ? ThreadLocalRandom.current().nextLong(opt.jitterMillis + 1) : 0);
        redis.set(key, v, SetArgs.Builder.px(ttl));                  // 第 7 站
        return v;
    }

    // ---------- 布隆过滤器：Redis 8 自带 BF.*，Lettuce 6 没有现成方法，用 dispatch 发 ----------

    enum Bf implements ProtocolKeyword {
        RESERVE("BF.RESERVE"), EXISTS("BF.EXISTS"), MADD("BF.MADD");
        final byte[] bytes;
        Bf(String s) { bytes = s.getBytes(StandardCharsets.US_ASCII); }
        @Override public byte[] getBytes() { return bytes; }
    }

    boolean bloomMightContain(long id) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8).addKey(BLOOM_KEY).add(id);
        return Boolean.TRUE.equals(redis.dispatch(Bf.EXISTS, new BooleanOutput<>(StringCodec.UTF8), args));
    }

    /** 上架时把商品 id 放进过滤器；按容量和误判率建，建好以后容量不够会自动加一层（变慢、变大） */
    public static void bloomInit(RedisCommands<String, String> redis, long capacity, double errorRate, long[] ids) {
        redis.del(BLOOM_KEY);
        redis.dispatch(Bf.RESERVE, new io.lettuce.core.output.StatusOutput<>(StringCodec.UTF8),
                new CommandArgs<>(StringCodec.UTF8).addKey(BLOOM_KEY).add(errorRate).add(capacity));
        for (int i = 0; i < ids.length; i += 1000) {
            CommandArgs<String, String> a = new CommandArgs<>(StringCodec.UTF8).addKey(BLOOM_KEY);
            for (int j = i; j < Math.min(ids.length, i + 1000); j++) a.add(ids[j]);
            redis.dispatch(Bf.MADD, new BooleanListOutput<>(StringCodec.UTF8), a);
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    // ---------- ⑥ 第三圈：逻辑过期，另一种挡击穿的办法 ----------

    /**
     * 值里自带一个“逻辑过期时间”，Redis 上不设 TTL。过期了也先把旧值返回，
     * 只有抢到锁的那一个线程到后台去查库重建。没有人等，代价是这一小段时间里读到旧值。
     * 值的格式：expireAtMillis|json
     */
    public static String getLogical(RedisCommands<String, String> redis, long id, LongFunction<String> db,
                                    long ttlMillis, ExecutorService rebuilder, int[] staleCounter) {
        String key = "product:" + id;
        String raw = redis.get(key);
        if (raw == null) {                                            // 冷启动没预热：只能同步查一次
            String v = db.apply(id);
            redis.set(key, (System.currentTimeMillis() + ttlMillis) + "|" + v);
            return v;
        }
        int bar = raw.indexOf('|');
        long expireAt = Long.parseLong(raw.substring(0, bar));
        String v = raw.substring(bar + 1);
        if (expireAt > System.currentTimeMillis()) return v;           // 没过期
        synchronized (staleCounter) { staleCounter[0]++; }             // 过期了：先拿旧值走人
        String lockKey = "lock:" + key, token = UUID.randomUUID().toString();
        if ("OK".equals(redis.set(lockKey, token, SetArgs.Builder.nx().px(3000)))) {
            rebuilder.submit(() -> {
                try {
                    String cur = redis.get(key);                       // 再看一眼：别人可能刚重建完
                    if (cur != null && Long.parseLong(cur.substring(0, cur.indexOf('|'))) > System.currentTimeMillis()) return;
                    String nv = db.apply(id);
                    redis.set(key, (System.currentTimeMillis() + ttlMillis) + "|" + nv);
                } finally {
                    redis.eval(UNLOCK_LUA, ScriptOutputType.INTEGER, new String[]{lockKey}, token);
                }
            });
        }
        return v;
    }
}
