package labs.redis.redis_lock_expire_fencing;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.UUID;
import java.util.function.LongFunction;

/**
 * 交易靶场商品服务读详情：全景“一次商品详情查询”第 5～7 站。
 * 第 5 站抢重建锁（SET NX PX），第 6 站查库，第 7 站回填并解锁。
 * 这一页只关心第 7 站“回填”这一下怎么写：A 查库查得比锁还久，锁过期了，A 回来还能不能写。
 */
public class ProductCache {

    /** 第 7 站回填的五种写法 */
    public enum Write {
        PLAIN("直接 SET"),
        CHECK_THEN_SET("先 GET 锁比 token，再 SET"),
        LOCK_GUARD("Lua：锁还是我的才写"),
        VERSION_GUARD("Lua：版本不比缓存旧才写");

        public final String label;
        Write(String label) { this.label = label; }
    }

    /** 解锁：锁里还是自己的 token 才删（第一圈讲过） */
    static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    /** 锁守卫：比 token、回填、解锁放进同一段脚本，中间插不进别的命令 */
    static final String LOCK_GUARD_LUA =
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end\n" +
            "redis.call('set', KEYS[2], ARGV[2], 'PX', ARGV[3])\n" +
            "redis.call('del', KEYS[1])\n" +
            "return 1";

    /** 版本守卫：缓存里那份（数据或墓碑）的 version 比我新，就不写 */
    static final String VERSION_GUARD_LUA =
            "local cur = redis.call('get', KEYS[1])\n" +
            "if cur then\n" +
            "  local ok, old = pcall(cjson.decode, cur)\n" +
            "  if ok and type(old) == 'table' and tonumber(old.version) and tonumber(old.version) > tonumber(ARGV[2]) then return 0 end\n" +
            "end\n" +
            "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[3])\n" +
            "return 1";

    /** 模拟“查完库还没写回去”的那段时间：慢 SQL、网络抖、GC 停顿 */
    public interface Pause { void run(String where); }

    final RedisCommands<String, String> redis;
    final LongFunction<String> db;                 // 第 6 站：按 id 查一行，拼成 JSON
    final Write write;
    long lockMillis = 3000;                        // 全景第 5 站：PX 3000
    long ttlMillis = 30 * 60_000L;                 // 全景第 7 站：30 分钟（这里不加随机，好比较）
    Pause pause = w -> { };
    public volatile Boolean lastFill;              // 上一次回填：true 写进去了，false 被拒，null 没走到

    public ProductCache(RedisCommands<String, String> redis, LongFunction<String> db, Write write) {
        this.redis = redis;
        this.db = db;
        this.write = write;
    }

    public ProductCache pause(Pause p) { this.pause = p; return this; }

    static String key(long id) { return "product:" + id; }
    static String lockKey(long id) { return "lock:product:" + id; }

    /** 墓碑：改价以后不 DEL，而是写一个“version=N，已作废”的占位 */
    public static boolean isTombstone(String v) { return v != null && v.contains("\"gone\":true"); }

    public static long version(String json) {
        int i = json.indexOf("\"version\":");
        int j = i + 10, k = j;
        while (k < json.length() && Character.isDigit(json.charAt(k))) k++;
        return Long.parseLong(json.substring(j, k));
    }

    public String get(long id) {
        String v = redis.get(key(id));                                   // 第 3 站
        if (v != null && !isTombstone(v)) return v;
        String token = UUID.randomUUID().toString();
        String ok = redis.set(lockKey(id), token, SetArgs.Builder.nx().px(lockMillis));   // 第 5 站
        if (!"OK".equals(ok)) return waitForOthers(id);
        String row = db.apply(id);                                       // 第 6 站
        lastFill = fill(id, token, row);                                 // 第 7 站
        return row;
    }

    boolean fill(long id, String token, String row) {
        String k = key(id), lk = lockKey(id);
        switch (write) {
            case PLAIN: {
                pause.run("slow");
                pause.run("gc");
                redis.set(k, row, SetArgs.Builder.px(ttlMillis));
                unlock(lk, token);
                return true;
            }
            case CHECK_THEN_SET: {
                pause.run("slow");
                boolean mine = token.equals(redis.get(lk));              // 这一刻锁确实还是我的
                pause.run("gc");                                         // ……但下一条命令发出去之前停了一下
                if (mine) redis.set(k, row, SetArgs.Builder.px(ttlMillis));
                unlock(lk, token);
                return mine;
            }
            case LOCK_GUARD: {
                pause.run("slow");
                pause.run("gc");
                Long r = redis.eval(LOCK_GUARD_LUA, ScriptOutputType.INTEGER,
                        new String[]{lk, k}, token, row, String.valueOf(ttlMillis));
                return r == 1;
            }
            case VERSION_GUARD: {
                pause.run("slow");
                pause.run("gc");
                Long r = redis.eval(VERSION_GUARD_LUA, ScriptOutputType.INTEGER,
                        new String[]{k}, row, String.valueOf(version(row)), String.valueOf(ttlMillis));
                unlock(lk, token);
                return r == 1;
            }
            default: throw new IllegalStateException();
        }
    }

    void unlock(String lk, String token) {
        redis.eval(UNLOCK_LUA, ScriptOutputType.INTEGER, new String[]{lk}, token);
    }

    /** 没抢到锁：每 10 ms 回头看一眼缓存（全景第 5 站） */
    String waitForOthers(long id) {
        for (int i = 0; i < 500; i++) {
            sleep(10);
            String v = redis.get(key(id));
            if (v != null && !isTombstone(v)) return v;
        }
        return db.apply(id);
    }

    /** 第 10 站：改完价让缓存作废。tombstoneMillis = 0 就是 DEL，否则写墓碑 */
    public void invalidate(long id, long newVersion, long tombstoneMillis) {
        if (tombstoneMillis <= 0) redis.del(key(id));
        else redis.set(key(id), "{\"id\":" + id + ",\"version\":" + newVersion + ",\"gone\":true}",
                SetArgs.Builder.px(tombstoneMillis));
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
