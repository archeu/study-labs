package labs.arch.seckill_coupon;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抢券的三段实验（全景 J12 第 6、7、9 站），规模按全景缩小 10 倍：100 张券。
 * 1. naive：GET 看库存、再 DECR、再 SADD，分三次调用 → 超发、一人多张
 * 2. lua  ：查重 + 扣库存放进一段 Lua，一次执行 → 正好 100 张，一人一张
 * 3. flag ：8 个“实例”各有一个售罄标记，数 Redis 一共被调用了几次
 * 4. async：抢到的人进队列，消费者落“库”；同一条消息投递两次，看唯一键和条件更新挡不挡得住
 * 第 4 段的“库”是内存里模拟的唯一键和条件更新，不是真 MySQL（真 MySQL 的版本见 README 练习）。
 */
public class CouponRushLab {

    static final String STOCK = "c88:stock", USERS = "c88:users";
    static final int COUPONS = 100, USERS_N = 1000, THREADS = 200, TRIES_PER_USER = 2;

    static final String LUA =
            "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 1 end\n" +
            "local s = tonumber(redis.call('GET', KEYS[1]) or '0')\n" +
            "if s <= 0 then return 2 end\n" +
            "redis.call('DECR', KEYS[1])\n" +
            "redis.call('SADD', KEYS[2], ARGV[1])\n" +
            "return 0";

    public static void main(String[] args) throws Exception {
        String url = System.getProperty("redis", "redis://127.0.0.1:63799");
        RedisClient client = RedisClient.create(url);
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> r = conn.sync();
            String sha = r.scriptLoad(LUA);
            System.out.println("Redis " + java.util.Arrays.stream(r.info("server").split("\r?\n")).filter(l -> l.startsWith("redis_version")).findFirst().orElse("?"));
            System.out.printf("券 %d 张，%d 个用户每人点 %d 次，%d 个线程同时抢%n%n", COUPONS, USERS_N, TRIES_PER_USER, THREADS);

            reset(r);
            Result a = rush(uid -> naive(r, uid));
            report("1. naive  GET → DECR → SADD", a, r);

            reset(r);
            Result b = rush(uid -> lua(r, sha, uid));
            report("2. lua    一段脚本做完", b, r);

            reset(r);
            flagLab(r, sha);

            reset(r);
            asyncLab(r, sha);
        } finally {
            client.shutdown();
        }
    }

    interface Grab { int grab(String uid); }   // 0 成功，1 领过，2 售罄

    static int naive(RedisCommands<String, String> r, String uid) {
        if (r.sismember(USERS, uid)) return 1;
        long s = Long.parseLong(Optional.ofNullable(r.get(STOCK)).orElse("0"));
        if (s <= 0) return 2;
        r.decr(STOCK);           // 两个线程可能都看到了 s = 1
        r.sadd(USERS, uid);
        return 0;
    }

    static int lua(RedisCommands<String, String> r, String sha, String uid) {
        Long v = r.evalsha(sha, ScriptOutputType.INTEGER, new String[]{STOCK, USERS}, uid);
        return v.intValue();
    }

    static class Result { int ok, dup, sold; Map<String, Integer> perUser = new ConcurrentHashMap<>(); long ms; }

    static Result rush(Grab g) throws Exception {
        List<String> reqs = new ArrayList<>();
        for (int i = 0; i < USERS_N; i++) for (int k = 0; k < TRIES_PER_USER; k++) reqs.add("u" + i);
        Collections.shuffle(reqs, new Random(42));
        Result res = new Result();
        AtomicInteger ok = new AtomicInteger(), dup = new AtomicInteger(), sold = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (String uid : reqs) fs.add(pool.submit(() -> {
            go.await();
            int v = g.grab(uid);
            if (v == 0) { ok.incrementAndGet(); res.perUser.merge(uid, 1, Integer::sum); }
            else if (v == 1) dup.incrementAndGet(); else sold.incrementAndGet();
            return null;
        }));
        long t0 = System.nanoTime();
        go.countDown();
        for (Future<?> f : fs) f.get();
        res.ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        res.ok = ok.get(); res.dup = dup.get(); res.sold = sold.get();
        return res;
    }

    static void report(String name, Result x, RedisCommands<String, String> r) {
        long multi = x.perUser.values().stream().filter(c -> c > 1).count();
        System.out.printf("%s%n  回“抢到了” %d 次（券只有 %d 张），一人抢到多张的用户 %d 个，回“领过” %d，回“已抢完” %d；Redis 里库存剩 %s，已领集合 %d 人；耗时 %d ms%n%n",
                name, x.ok, COUPONS, multi, x.dup, x.sold, r.get(STOCK), r.scard(USERS), x.ms);
    }

    /** 8 个实例，每个有自己的售罄标记；第一次听到“售罄”就置位，之后本机直接回。 */
    static void flagLab(RedisCommands<String, String> r, String sha) throws Exception {
        int instances = 8, requests = 20_000;
        for (boolean useFlag : new boolean[]{false, true}) {
            reset(r);
            AtomicBoolean[] flags = new AtomicBoolean[instances];
            for (int i = 0; i < instances; i++) flags[i] = new AtomicBoolean(false);
            AtomicInteger calls = new AtomicInteger(), ok = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    go.await();
                    AtomicBoolean flag = flags[n % instances];
                    if (useFlag && flag.get()) return null;        // 本机直接回“已抢完”
                    calls.incrementAndGet();
                    int v = lua(r, sha, "u" + n);
                    if (v == 0) ok.incrementAndGet();
                    if (v == 2) flag.set(true);
                    return null;
                }));
            }
            long t0 = System.nanoTime();
            go.countDown();
            for (Future<?> f : fs) f.get();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            pool.shutdown();
            System.out.printf("3. flag %-4s %d 个请求，Redis 被调用 %5d 次，抢到 %d，耗时 %d ms%n",
                    useFlag ? "开" : "关", requests, calls.get(), ok.get(), ms);
        }
        System.out.println();
    }

    /** 抢到的进队列（代替 MQ），消费者落库：条件更新 issued < 100 + 唯一键 (user, coupon)。每条消息故意投递两次。 */
    static void asyncLab(RedisCommands<String, String> r, String sha) throws Exception {
        BlockingQueue<String> mq = new LinkedBlockingQueue<>();
        Result x = rush(uid -> { int v = lua(r, sha, uid); if (v == 0) { mq.add(uid); mq.add(uid); } return v; });
        int[] issued = {0};
        Set<String> uniq = new HashSet<>();
        int inserted = 0, dupKey = 0, full = 0;
        String uid;
        while ((uid = mq.poll()) != null) {
            if (uniq.contains(uid)) { dupKey++; continue; }          // INSERT 撞唯一键 → 当成功处理
            if (issued[0] >= COUPONS) { full++; continue; }         // UPDATE ... WHERE issued < 100 影响 0 行
            issued[0]++; uniq.add(uid); inserted++;
        }
        // 模拟第 11 站：Redis 切主后库存“回涨” 5 张，又有 5 个人在 Redis 里抢到了
        r.set(STOCK, "5");
        for (int i = 0; i < 5; i++) if (lua(r, sha, "late" + i) == 0) mq.add("late" + i);
        int late = 0, lateRejected = 0;
        while ((uid = mq.poll()) != null) {
            late++;
            if (uniq.contains(uid)) continue;
            if (issued[0] >= COUPONS) { lateRejected++; continue; }
            issued[0]++; uniq.add(uid);
        }
        System.out.printf("4. async 抢到 %d 人，投递 %d 条消息（每条两次）：落库 %d 行，撞唯一键 %d 次，条件更新挡下 %d 次%n",
                x.ok, x.ok * 2, inserted, dupKey, full);
        System.out.printf("   模拟切主后库存回涨 5 张：Redis 又放出 %d 人，落库时条件更新挡下 %d 人，最终发券 %d 张%n",
                late, lateRejected, issued[0]);
    }

    static void reset(RedisCommands<String, String> r) {
        r.del(STOCK, USERS);
        r.set(STOCK, String.valueOf(COUPONS));
    }
}
