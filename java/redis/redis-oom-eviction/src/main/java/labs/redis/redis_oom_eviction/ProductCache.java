package labs.redis.redis_oom_eviction;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongFunction;

/**
 * 交易靶场商品服务读详情：全景“一次商品详情查询”第 2～7 站（布隆过滤器这里省掉）。
 * 第 5 站 SET NX PX 抢重建锁，第 7 站 SET … PX 回填、Lua 解锁。
 *
 * fallback=false：Redis 报错原样抛出，请求失败（多数项目的默认写法）。
 * fallback=true ：Redis 写失败就当缓存不存在，直接查库返回（常见的“降级”写法）。
 */
public class ProductCache {

    static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    final RedisCommands<String, String> redis;
    final LongFunction<String> db;
    final boolean fallback;
    final long ttlMillis;

    public ProductCache(RedisCommands<String, String> redis, LongFunction<String> db, boolean fallback, long ttlMillis) {
        this.redis = redis;
        this.db = db;
        this.fallback = fallback;
        this.ttlMillis = ttlMillis;
    }

    public String get(long id) {
        String key = "product:" + id;
        String v = redis.get(key);                                    // 第 2、3 站：GET 不带 DENYOOM，内存满了照样能读
        if (v != null) return v;
        String lockKey = "lock:" + key, token = UUID.randomUUID().toString();
        while (true) {
            String ok;
            try {
                ok = redis.set(lockKey, token, SetArgs.Builder.nx().px(3000));   // 第 5 站：SET 带 DENYOOM
            } catch (RedisCommandExecutionException e) {
                if (!fallback) throw e;
                return db.apply(id);                                  // 降级：锁都抢不到，谁没命中谁查库
            }
            if ("OK".equals(ok)) {
                try {
                    v = redis.get(key);
                    if (v != null) return v;
                    v = db.apply(id);                                 // 第 6 站
                    try {
                        long ttl = ttlMillis + ThreadLocalRandom.current().nextLong(ttlMillis / 6 + 1);
                        redis.set(key, v, SetArgs.Builder.px(ttl));   // 第 7 站：回填
                    } catch (RedisCommandExecutionException e) {
                        if (!fallback) throw e;
                    }
                    return v;
                } finally {                                           // 第 7 站：Lua 里只有 GET、DEL，内存满了也能跑
                    redis.eval(UNLOCK_LUA, ScriptOutputType.INTEGER, new String[]{lockKey}, token);
                }
            }
            sleep(10);
            v = redis.get(key);
            if (v != null) return v;
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
