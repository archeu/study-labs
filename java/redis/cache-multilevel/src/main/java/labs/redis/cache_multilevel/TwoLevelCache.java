package labs.redis.cache_multilevel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 交易靶场商品服务的两级缓存：一级 Caffeine（每个实例一份），二级 Redis（大家共用一份）。
 * 对应全景“一次商品详情查询”的第 1、8、11 站。为了只看两级缓存，省掉了布隆过滤器和重建锁（第 4、5 站）。
 */
public class TwoLevelCache implements AutoCloseable {
    public static final String CHANNEL = "cache:evict";

    final String name;
    final Cache<String, String> l1;
    final StatefulRedisConnection<String, String> conn;
    final RedisCommands<String, String> redis;
    final StatefulRedisPubSubConnection<String, String> sub;
    final Function<String, String> db;          // 数据库：唯一的真相
    final boolean flushOnReconnect;
    final AtomicInteger subscribes = new AtomicInteger();
    /** 这次读是在哪一层拿到的：L1 / Redis / DB（只给演示打印用） */
    static final ThreadLocal<String> SOURCE = new ThreadLocal<>();
    /** 失效消息到了以后，invalidate 本身花了多久（纳秒，最近一次） */
    volatile long lastInvalidateNanos;

    public TwoLevelCache(String name, RedisClient client, Function<String, String> db,
                         long l1TtlMillis, boolean flushOnReconnect) {
        this.name = name;
        this.db = db;
        this.flushOnReconnect = flushOnReconnect;
        // 一级：一定要有条数上限和过期时间。过期时间是失效消息丢了时的兜底：旧值最多活这么久
        this.l1 = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(l1TtlMillis, TimeUnit.MILLISECONDS)
                .build();
        this.conn = client.connect();
        this.redis = conn.sync();
        // 第 11 站：每个实例单独一条订阅连接，收到 key 就把自己那份作废
        this.sub = client.connectPubSub();
        sub.addListener(new RedisPubSubAdapter<String, String>() {
            @Override public void message(String channel, String key) {
                long t0 = System.nanoTime();
                l1.invalidate(key);
                lastInvalidateNanos = System.nanoTime() - t0;
            }
            // Redis 确认订阅时回调。第一次是我们自己订的；之后每一次都是 Lettuce 重连后自动补订的
            @Override public void subscribed(String channel, long count) {
                if (subscribes.incrementAndGet() > 1 && flushOnReconnect) l1.invalidateAll();
            }
        });
        // Pub/Sub 最多投递一次：断线那一会儿发的失效消息就丢了。所以断线时清一次（别再拿可能过时的值），
        // 补订成功时再清一次（断线到补订之间加载进来的，也可能错过了消息）。宁可多查几次 Redis
        sub.addListener(new RedisConnectionStateListener() {
            @Override public void onRedisDisconnected(RedisChannelHandler<?, ?> c) {
                if (flushOnReconnect) l1.invalidateAll();
            }
        });
        sub.sync().subscribe(CHANNEL);
    }

    /** 读：L1 → Redis → DB。用 get(key, loader)，同一个 key 同一时刻只放一个线程去加载 */
    public String get(String key) {
        SOURCE.set("L1");
        return l1.get(key, this::loadFromL2);
    }

    String loadFromL2(String key) {
        String v = redis.get(key);                       // 第 2、3 站
        if (v != null) { SOURCE.set("Redis"); return v; }
        v = db.apply(key);                               // 第 6 站
        redis.setex(key, 1800 + ThreadLocalRandom.current().nextInt(300), v);  // 第 7 站
        SOURCE.set("DB");
        return v;                                        // 第 8 站：返回值由 Caffeine 放进本地
    }

    /** 写：改完库以后调。先删 Redis，再广播让各实例删本地（第 10、11 站）。返回收到消息的实例数 */
    public static long evict(RedisCommands<String, String> admin, String key) {
        admin.del(key);
        return admin.publish(CHANNEL, key);
    }

    @Override public void close() {
        sub.close();
        conn.close();
    }
}
