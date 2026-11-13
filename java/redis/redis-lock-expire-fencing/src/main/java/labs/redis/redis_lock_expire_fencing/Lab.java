package labs.redis.redis_lock_expire_fencing;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/**
 * 三段实验，对应页面：
 *   stale  ②⑤ 88 号的重建锁过期了，A 还在查库：三条时间线 × 五种回填写法，缓存最后是几块钱
 *   dog    ⑥ Redisson 看门狗：设了 leaseTime 就没有它；有它时锁一直续着；持锁进程被 SIGSTOP 停住，锁照样过期
 *   lua    ⑥ Lua 脚本跑太久：别的客户端什么时候收到 BUSY，SCRIPT KILL 什么时候杀得掉；脚本缓存被清空后 EVALSHA 报 NOSCRIPT
 * 另有 holder：dog 段用它起一个单独的持锁进程，不要直接跑。
 */
public class Lab {
    static final String HOST = System.getProperty("host", "127.0.0.1");
    static final int PORT = Integer.getInteger("port", 63798);
    static final String URI = "redis://" + HOST + ":" + PORT;
    static final long ID = 88;

    public static void main(String[] args) throws Exception {
        List<String> parts = args.length > 0 ? Arrays.asList(args) : Arrays.asList("stale", "dog", "lua");
        if (parts.get(0).equals("holder")) { holder(Long.parseLong(args[1])); return; }
        RedisClient client = RedisClient.create(RedisURI.create(URI));
        try {
            for (String p : parts) {
                switch (p) {
                    case "stale": stale(client); break;
                    case "dog": dog(client); break;
                    case "lua": lua(client); break;
                    default: System.out.println("不认识的段：" + p);
                }
            }
        } finally {
            client.shutdown();
        }
    }

    // ------------------------------------------------------------------ stale
    static String row(String price, long version) {
        return "{\"id\":88,\"name\":\"88 号 机械键盘\",\"price\":\"" + price + "\",\"version\":" + version + "}";
    }

    static String price(String json) {
        int i = json.indexOf("\"price\":\"");
        return json.substring(i + 9, json.indexOf('"', i + 9));
    }

    /** 三条时间线：A 什么时候查完、什么时候写；有没有 B 来接手 */
    static final String[] TL_NAME = {"T1 有人接手", "T2 没人接手", "T3 锁没过期"};
    static final long[] TL_SLOW = {2900, 2900, 1500};    // A 查库 + 处理结果，到这一刻还在锁里
    static final long[] TL_WRITE = {4000, 4000, 2000};   // A 发出回填命令的时刻（中间那段是 GC 停顿）
    static final long[] TL_B = {3300, -1, -1};           // B 什么时候来读 88 号
    static final long[] TL_END = {4600, 4600, 2600};     // 什么时候看缓存里最后是什么

    static void stale(RedisClient client) throws Exception {
        System.out.println("== stale：88 号重建锁 PX 3000，A 查到 399 元（version 1）；第 1 秒后台把价格改成 299（version 2）并让缓存作废");
        Object[][] modes = {
                {ProductCache.Write.PLAIN, 0L},
                {ProductCache.Write.CHECK_THEN_SET, 0L},
                {ProductCache.Write.LOCK_GUARD, 0L},
                {ProductCache.Write.VERSION_GUARD, 0L},
                {ProductCache.Write.VERSION_GUARD, 10_000L},
        };
        for (int tl = 0; tl < 3; tl++) {
            System.out.printf("-- %s：A 第 %.1f s 还在锁里，第 %.1f s 发回填%s%n", TL_NAME[tl], TL_SLOW[tl] / 1000.0,
                    TL_WRITE[tl] / 1000.0, TL_B[tl] > 0 ? "；B 第 " + TL_B[tl] / 1000.0 + " s 来读" : "");
            for (Object[] m : modes) {
                ProductCache.Write w = (ProductCache.Write) m[0];
                long tomb = (Long) m[1];
                String name = w.label + (w == ProductCache.Write.VERSION_GUARD ? (tomb > 0 ? "，作废写墓碑" : "，作废用 DEL") : "");
                String[] r = timeline(client, w, tomb, tl);
                System.out.printf("   %-34s %s%n", name, String.join("  ", r));
            }
        }
    }

    static String[] timeline(RedisClient client, ProductCache.Write w, long tomb, int tl) throws Exception {
        AtomicReference<String> table = new AtomicReference<>(row("399.00", 1));
        AtomicInteger dbHits = new AtomicInteger();
        LongFunction<String> db = id -> { dbHits.incrementAndGet(); return table.get(); };
        try (StatefulRedisConnection<String, String> ca = client.connect();
             StatefulRedisConnection<String, String> cb = client.connect();
             StatefulRedisConnection<String, String> cx = client.connect()) {
            RedisCommands<String, String> admin = cx.sync();
            admin.flushdb();
            long t0 = System.nanoTime();
            ProductCache a = new ProductCache(ca.sync(), db, w).pause(where ->
                    sleepUntil(t0, where.equals("slow") ? TL_SLOW[tl] : TL_WRITE[tl]));
            ProductCache b = new ProductCache(cb.sync(), db, w);
            ProductCache x = new ProductCache(admin, db, w);
            ExecutorService ex = Executors.newFixedThreadPool(3);
            Future<String> fa = ex.submit(() -> a.get(ID));
            Future<?> fx = ex.submit(() -> {
                sleepUntil(t0, 1000);
                table.set(row("299.00", 2));                  // 第 9 站：UPDATE product SET price=299, version=version+1
                x.invalidate(ID, 2, tomb);                    // 第 10 站：DEL，或写 version=2 的墓碑
            });
            Future<String> fb = TL_B[tl] < 0 ? null : ex.submit(() -> { sleepUntil(t0, TL_B[tl]); return b.get(ID); });
            fa.get(); fx.get();
            String bSaw = fb == null ? null : fb.get();
            sleepUntil(t0, TL_END[tl]);
            String v = admin.get(ProductCache.key(ID));
            long pttl = admin.pttl(ProductCache.key(ID));
            ex.shutdown();
            String aFill = a.lastFill == null ? "-" : a.lastFill ? "写入" : "被拒";
            String verdict;
            if (v == null) verdict = "缓存：空          ✓ 下次读查库拿 299";
            else if (ProductCache.isTombstone(v)) verdict = "缓存：墓碑 v2     ✓ 下次读查库拿 299";
            else if (price(v).equals("299.00")) verdict = "缓存：299         ✓";
            else verdict = String.format("缓存：399         ✗ 旧价还要挂 %d 分钟", Math.round(pttl / 60_000.0));
            return new String[]{"A 回填" + aFill, bSaw == null ? "" : "B 拿到 " + price(bSaw).replace(".00", ""),
                    "查库 " + dbHits.get() + " 次", verdict};
        }
    }

    // ------------------------------------------------------------------ dog
    static Config redissonConfig(long watchdogMillis) {
        Config c = new Config();
        c.useSingleServer().setAddress(URI).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        c.setLockWatchdogTimeout(watchdogMillis);
        return c;
    }

    /** 单独的持锁进程：不带 leaseTime 加锁（看门狗续期），假装查库要 work 毫秒，然后解锁 */
    static void holder(long workMillis) throws Exception {
        RedissonClient r = Redisson.create(redissonConfig(3000));
        RLock lock = r.getLock("lock:product:88");
        lock.lock();
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        System.out.println("LOCKED " + pid);
        System.out.flush();
        Thread.sleep(workMillis);
        try {
            lock.unlock();
            System.out.println("UNLOCKED");
        } catch (IllegalMonitorStateException e) {
            System.out.println("UNLOCK_FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.flush();
        r.shutdown();
    }

    static void dog(RedisClient client) throws Exception {
        System.out.println("== dog：Redisson 4.8.0，lockWatchdogTimeout 改成 3000 ms（默认 30000），续期间隔是它的 1/3");
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            RedisCommands<String, String> r = c.sync();
            r.flushdb();
            RedissonClient rs = Redisson.create(redissonConfig(3000));
            try {
                // 1) 设了 leaseTime：没有看门狗
                RLock lock = rs.getLock("lock:product:88");
                lock.lock(2, TimeUnit.SECONDS);
                long t0 = System.nanoTime();
                StringBuilder sb = new StringBuilder();
                for (long at : new long[]{0, 1000, 1900, 2100}) {
                    sleepUntil(t0, at);
                    sb.append(String.format(" %.1fs:%d", at / 1000.0, r.pttl("lock:product:88")));
                }
                System.out.println("   lock(2, SECONDS) 后干 2.1 秒的活，PTTL：" + sb + "   还拿着锁吗：" + lock.isHeldByCurrentThread());

                // 2) 不设 leaseTime：另一个进程拿锁，看 PTTL 被续
                String java = System.getProperty("java.home") + "/bin/java";
                Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        Lab.class.getName(), "holder", "12000").redirectErrorStream(true).start();
                BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String pid = null;
                for (String line; (line = out.readLine()) != null; ) {
                    if (line.startsWith("LOCKED ")) { pid = line.substring(7).trim(); break; }
                }
                if (pid == null) throw new IllegalStateException("holder 没拿到锁");
                t0 = System.nanoTime();
                long min = Long.MAX_VALUE, max = 0;
                for (long at = 250; at <= 4000; at += 250) {
                    sleepUntil(t0, at);
                    long t = r.pttl("lock:product:88");
                    min = Math.min(min, t); max = Math.max(max, t);
                }
                System.out.printf("   lock() 不带 leaseTime，持锁 4 秒里每 250 ms 看一次 PTTL：最低 %d，最高 %d ms（一直没到 0）%n", min, max);

                // 3) 把持锁进程整个停住（像一次很长的 Full GC，看门狗的定时器也停了）
                exec("kill", "-STOP", pid);
                long stopAt = System.nanoTime();
                long expiredMs = -1;
                while (ms(stopAt) < 6000) {
                    if (r.pttl("lock:product:88") == -2) { expiredMs = ms(stopAt); break; }
                    Thread.sleep(20);
                }
                RLock mine = rs.getLock("lock:product:88");
                boolean got = mine.tryLock(0, 10, TimeUnit.SECONDS);
                long gotMs = ms(stopAt);
                sleepUntil(stopAt, 5000);
                exec("kill", "-CONT", pid);
                String result = out.readLine();
                p.waitFor(10, TimeUnit.SECONDS);
                System.out.printf("   kill -STOP 持锁进程：%d ms 后锁过期；%d ms 时另一个进程 tryLock = %s；5 s 后 kill -CONT%n",
                        expiredMs, gotMs, got);
                System.out.println("   持锁进程醒来接着干完、解锁：" + result);
                if (got) mine.unlock();
            } finally {
                rs.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------ lua
    static final String SPIN_LUA =
            "local s = redis.call('TIME')\n" +
            "local t0 = s[1] * 1000000 + s[2]\n" +
            "if ARGV[2] == 'write' then redis.call('SET', 'touched', '1') end\n" +
            "while true do\n" +
            "  local n = redis.call('TIME')\n" +
            "  if n[1] * 1000000 + n[2] - t0 > tonumber(ARGV[1]) * 1000 then break end\n" +
            "end\n" +
            "return 1";

    static void lua(RedisClient client) throws Exception {
        try (StatefulRedisConnection<String, String> c1 = client.connect();
             StatefulRedisConnection<String, String> c2 = client.connect();
             StatefulRedisConnection<String, String> c3 = client.connect()) {
            RedisCommands<String, String> r2 = c2.sync(), r3 = c3.sync();
            System.out.println("== lua：busy-reply-threshold = " + r3.configGet("busy-reply-threshold").get("busy-reply-threshold") + " ms");
            for (String mode : new String[]{"read", "write"}) {
                long run = mode.equals("read") ? 6000 : 7000;
                long t0 = System.nanoTime();
                RedisFuture<Long> f = c1.async().eval(SPIN_LUA, ScriptOutputType.INTEGER, new String[0], String.valueOf(run), mode);
                sleepUntil(t0, 1000);
                String ping;
                try { ping = r2.ping(); } catch (Exception e) { ping = firstWord(e); }
                long pingMs = ms(t0);
                sleepUntil(t0, 5500);
                String kill;
                try { kill = r3.scriptKill(); } catch (Exception e) { kill = firstWord(e); }
                long killMs = ms(t0);
                String res;
                try { res = "返回 " + f.get(); } catch (ExecutionException e) { res = firstWord(e.getCause()); }
                long endMs = ms(t0);
                String ping2 = r2.ping();
                System.out.printf("   脚本%s、空转 %.0f s：第 1.0 s 发 PING，%.1f s 收到 %s；第 5.5 s SCRIPT KILL → %s（%.1f s）；脚本 %.1f s 结束，%s；之后 PING → %s%n",
                        mode.equals("read") ? "只读" : "先写了一个 key", run / 1000.0, pingMs / 1000.0, ping,
                        kill, killMs / 1000.0, endMs / 1000.0, res, ping2);
            }
            RedisCommands<String, String> r = c1.sync();
            String sha = r.scriptLoad(ProductCache.UNLOCK_LUA);
            Long ok = r.evalsha(sha, ScriptOutputType.INTEGER, new String[]{"lock:product:88"}, "t");
            r.scriptFlush();
            String after;
            try { after = "返回 " + r.evalsha(sha, ScriptOutputType.INTEGER, new String[]{"lock:product:88"}, "t"); }
            catch (Exception e) { after = firstWord(e); }
            System.out.printf("   EVALSHA %s… → %d；SCRIPT FLUSH（像重启、主从切换后的新主）之后再 EVALSHA → %s%n",
                    sha.substring(0, 8), ok, after);
        }
    }

    static String firstWord(Throwable e) {
        String m = String.valueOf(e.getMessage());
        return m.length() > 90 ? m.substring(0, 90) + "…" : m;
    }

    // ------------------------------------------------------------------ util
    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    static void sleepUntil(long t0, long atMillis) {
        long left = atMillis - ms(t0);
        if (left > 0) ProductCache.sleep(left);
    }

    static void exec(String... cmd) throws Exception {
        new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
