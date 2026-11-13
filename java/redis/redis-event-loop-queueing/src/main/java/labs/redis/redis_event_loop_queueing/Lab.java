package labs.redis.redis_event_loop_queueing;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 三段实验，对应页面：
 *   share  ⑤ 一次 GET 的 279 µs 花在哪：裸 socket、Lettuce 单线程、4 条连接 × 50 线程、1 条连接 × 200 线程；Redis 主线程忙了多少
 *   slow   ⑥ 有人跑了一条 KEYS（100 万个 key）：所有连接上的 GET 一起等；SLOWLOG 和 latencystats 里却看不到它们在等；换成 SCAN 分批
 *   block  ⑥ 共享连接上的队头阻塞：同一条连接上跑 BLPOP、取一个 16 MB 的大 value，别的线程怎样；500 ms 超时会发生什么；换成独立连接
 */
public class Lab {
    static final String HOST = System.getProperty("host", "127.0.0.1");
    static final int PORT = Integer.getInteger("port", 63799);
    static final String KEY = "product:88";

    public static void main(String[] args) throws Exception {
        List<String> parts = args.length > 0 ? Arrays.asList(args) : Arrays.asList("share", "slow", "block");
        RedisClient client = RedisClient.create(RedisURI.create("redis://" + HOST + ":" + PORT));
        try {
            for (String p : parts) {
                switch (p) {
                    case "share": share(client); break;
                    case "slow": slow(client); break;
                    case "block": block(client); break;
                    default: System.out.println("不认识的段：" + p);
                }
            }
        } finally {
            client.shutdown();
        }
    }

    /** 88 号的详情 JSON，和全景页一样约 648 字节。 */
    static String detail() {
        StringBuilder sb = new StringBuilder("{\"id\":88,\"name\":\"88 号 机械键盘\",\"price\":\"399.00\",\"stock\":120,\"desc\":\"87 键热插拔，PBT 键帽，三模连接。");
        String tail = "\",\"version\":7}";
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length + tail.length() < 648) sb.append('x');
        return sb.append(tail).toString();
    }

    // ================================================================== share
    static void share(RedisClient client) throws Exception {
        System.out.println("\n===== share：一次 GET 的时间花在哪 =====");
        StatefulRedisConnection<String, String> admin = client.connect();
        RedisCommands<String, String> a = admin.sync();
        a.flushall();
        String v = detail();
        a.set(KEY, v);
        System.out.printf("88 号详情 %d 字节，Redis %s，io-threads=%s%n", v.getBytes(StandardCharsets.UTF_8).length,
                field(a.info("server"), "redis_version"), a.configGet("io-threads").get("io-threads"));

        // 1. 裸 socket：自己拼 RESP 字节，阻塞读，一个线程一来一回
        long[] raw = rawSocketGets(20000);
        System.out.println("[1] 裸 socket，单线程顺序 GET 20000 次        " + pct(raw));

        // 2. Lettuce，一条连接，一个线程
        RedisCommands<String, String> s = admin.sync();
        for (int i = 0; i < 2000; i++) s.get(KEY);              // 预热
        long[] one = new long[20000];
        for (int i = 0; i < one.length; i++) { long t = System.nanoTime(); s.get(KEY); one[i] = System.nanoTime() - t; }
        System.out.println("[2] Lettuce，1 条连接 × 1 个线程，GET 20000 次  " + pct(one));

        // 3. 全景的样子：4 个实例各 1 条连接，每个实例 50 个线程，一刻不停地查（压测的样子）
        runAndReport(client, a, "[3] 4 条连接 × 50 个线程，不停地查          ", 4, 50, 5000);
        // 4. 只有一条连接，200 个线程都挤在上面
        runAndReport(client, a, "[4] 1 条连接 × 200 个线程，不停地查         ", 1, 200, 5000);
        // 5. 每个线程一条连接（Jedis 连接池的样子）
        runAndReport(client, a, "[5] 200 条连接 × 1 个线程，不停地查         ", 200, 1, 5000);
        admin.close();
    }

    static void runAndReport(RedisClient client, RedisCommands<String, String> a, String label,
                             int conns, int threadsPer, long ms) throws Exception {
        a.configResetstat();
        String cpu0 = a.info("cpu");
        Load load = new Load(client, conns, threadsPer);
        load.run(ms, null, 0);
        String cpu1 = a.info("cpu");
        String cs = a.info("commandstats");
        String get = line(cs, "cmdstat_get:");
        long calls = Long.parseLong(kv(get, "calls"));
        long usec = Long.parseLong(kv(get, "usec"));
        double mainCpu = (Double.parseDouble(field(cpu1, "used_cpu_user_main_thread")) + Double.parseDouble(field(cpu1, "used_cpu_sys_main_thread"))
                - Double.parseDouble(field(cpu0, "used_cpu_user_main_thread")) - Double.parseDouble(field(cpu0, "used_cpu_sys_main_thread")));
        int clients = Integer.parseInt(field(a.info("clients"), "connected_clients"));
        System.out.println(label + pct(load.lats()) + String.format("，%,d 次/秒", (long) (load.count() * 1000.0 / ms)));
        System.out.printf("      Redis：GET 执行 %s µs/次（commandstats），执行命令合计 %.1f%% 的时间；主线程 CPU %.0f%%；connected_clients=%d（含 1 条管理连接）%n",
                kv(get, "usec_per_call"), usec / 10.0 / ms, mainCpu * 100000 / ms, clients);
        load.close();
    }

    static long[] rawSocketGets(int n) throws Exception {
        byte[] req = ("*2\r\n$3\r\nGET\r\n$" + KEY.length() + "\r\n" + KEY + "\r\n").getBytes(StandardCharsets.US_ASCII);
        long[] out = new long[n];
        try (Socket sock = new Socket(HOST, PORT)) {
            sock.setTcpNoDelay(true);
            OutputStream os = sock.getOutputStream();
            InputStream is = sock.getInputStream();
            byte[] buf = new byte[8192];
            for (int i = -2000; i < n; i++) {
                long t = System.nanoTime();
                os.write(req);
                os.flush();
                readBulk(is, buf);
                if (i >= 0) out[i] = System.nanoTime() - t;
            }
        }
        return out;
    }

    /** 读一个 RESP bulk string：$<len>\r\n<bytes>\r\n */
    static void readBulk(InputStream is, byte[] buf) throws Exception {
        int len = 0, c;
        if ((c = is.read()) != '$') throw new IllegalStateException("意外的回复类型 " + (char) c);
        while ((c = is.read()) != '\r') len = len * 10 + (c - '0');
        is.read();
        int left = len + 2;
        while (left > 0) left -= is.read(buf, 0, Math.min(buf.length, left));
    }

    // ================================================================== slow
    static void slow(RedisClient client) throws Exception {
        System.out.println("\n===== slow：有人跑了一条 KEYS =====");
        StatefulRedisConnection<String, String> admin = client.connect();
        RedisCommands<String, String> a = admin.sync();
        a.flushall();
        a.set(KEY, detail());
        // 灌 100 万个 key：用异步命令一次发 1000 条 MSET，每条 1000 个 key
        RedisAsyncCommands<String, String> as = admin.async();
        List<io.lettuce.core.RedisFuture<String>> fs = new ArrayList<>();
        for (int b = 0; b < 1000; b++) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < 1000; i++) m.put("product:" + (100000 + b * 1000 + i), "x");
            fs.add(as.mset(m));
        }
        for (io.lettuce.core.RedisFuture<String> f : fs) f.get();
        System.out.println("dbsize=" + a.dbsize());
        a.configSet("slowlog-log-slower-than", "10000");

        for (String mode : new String[]{"KEYS", "SCAN"}) {
            a.slowlogReset();
            a.configResetstat();
            StatefulRedisConnection<String, String> ops = client.connect();   // 运维同学自己的连接
            final long[] window = new long[2];
            final long[] scanCalls = new long[1];
            Load load = new Load(client, 4, 50);
            load.run(3000, () -> {
                window[0] = System.nanoTime();
                if (mode.equals("KEYS")) {
                    ops.sync().keys("session:*");            // 一个都匹配不上，但要把 100 万个 key 全看一遍
                } else {
                    ScanCursor cur = ScanCursor.INITIAL;
                    do {
                        KeyScanCursor<String> r = ops.sync().scan(cur, ScanArgs.Builder.matches("session:*").limit(1000));
                        scanCalls[0]++;
                        cur = r;
                    } while (!cur.isFinished());
                }
                window[1] = System.nanoTime();
            }, 1000);
            ops.close();
            long[] in = load.latsStartedBetween(window[0] - 5_000_000L, window[1]);
            System.out.printf("%n[%s] 运维这一步用了 %.0f ms%s%n", mode, (window[1] - window[0]) / 1e6,
                    mode.equals("SCAN") ? "（SCAN 调了 " + scanCalls[0] + " 次）" : "");
            System.out.println("  全部 GET                         " + pct(load.lats()));
            System.out.println("  在它前后 5 ms 内发出的 GET（" + in.length + " 个） " + pct(in));
            String get = line(a.info("commandstats"), "cmdstat_get:");
            System.out.println("  Redis 眼里的 GET：commandstats usec_per_call=" + kv(get, "usec_per_call")
                    + "；latencystats " + line(a.info("latencystats"), "latency_percentiles_usec_get:"));
            List<Object> sl = a.slowlogGet(10);
            System.out.println("  SLOWLOG 里有 " + sl.size() + " 条：");
            for (Object o : sl) {
                List<?> e = (List<?>) o;
                System.out.println("    " + e.get(2) + " µs  " + e.get(3));
            }
            load.close();
        }
        admin.close();
    }

    // ================================================================== block
    static void block(RedisClient client) throws Exception {
        System.out.println("\n===== block：共享连接上的队头阻塞 =====");
        StatefulRedisConnection<String, String> admin = client.connect();
        RedisCommands<String, String> a = admin.sync();
        a.flushall();
        a.set(KEY, detail());
        char[] big = new char[16 * 1024 * 1024];
        Arrays.fill(big, 'x');
        a.set("product:88:images", new String(big));            // 16 MB 的大 value
        System.out.println("product:88:images 长度 " + a.strlen("product:88:images") + " 字节");

        // 一个实例：1 条共享连接、50 个业务线程在查 88 号；第 1 秒有一个线程做了别的事
        String[][] cases = {
                {"BLPOP 2 秒，和大家共用一条连接", "blpop", "shared"},
                {"BLPOP 2 秒，自己开一条连接", "blpop", "dedicated"},
                {"GET 16 MB 大 value，共用一条连接", "big", "shared"},
                {"GET 16 MB 大 value，自己开一条连接", "big", "dedicated"},
        };
        for (String[] c : cases) {
            Load load = new Load(client, 1, 50);
            StatefulRedisConnection<String, String> shared = load.conns.get(0);
            StatefulRedisConnection<String, String> own = c[2].equals("shared") ? shared : client.connect();
            final long[] w = new long[2];
            load.run(4000, () -> {
                w[0] = System.nanoTime();
                if (c[1].equals("blpop")) own.sync().blpop(2, "queue:price-change");
                else own.sync().get("product:88:images");
                w[1] = System.nanoTime();
            }, 1000);
            long[] in = load.latsStartedBetween(w[0], w[1]);
            System.out.printf("%n[%s] 它自己用了 %.1f ms%n", c[0], (w[1] - w[0]) / 1e6);
            System.out.println("  这期间发出的 GET（" + in.length + " 个）          " + pct(in));
            System.out.println("  全部 GET                              " + pct(load.lats()));
            if (own != shared) own.close();
            load.close();
        }

        // 把命令超时从默认 60 秒改成 500 ms，再在共享连接上跑一次 BLPOP 2 秒
        System.out.println("\n[超时 500 ms + 共享连接上 BLPOP 2 秒]");
        Load load = new Load(client, 1, 50);
        StatefulRedisConnection<String, String> shared = load.conns.get(0);
        System.out.println("  默认超时 = " + shared.getTimeout());
        shared.setTimeout(Duration.ofMillis(500));
        final String[] blpopResult = new String[1];
        final long[] w = new long[2];
        load.run(4000, () -> {
            w[0] = System.nanoTime();
            try {
                shared.sync().blpop(2, "queue:price-change");
                blpopResult[0] = "正常返回";
            } catch (RedisCommandTimeoutException e) {
                blpopResult[0] = "客户端超时：" + e.getMessage();
            }
            w[1] = System.nanoTime();
        }, 1000);
        System.out.printf("  BLPOP 自己：%s（%.0f ms）%n", blpopResult[0], (w[1] - w[0]) / 1e6);
        System.out.println("  GET 超时次数 " + load.timeouts.get() + "，最后一次超时发生在 BLPOP 开始后 "
                + (load.lastTimeoutAt.get() == 0 ? "-" : String.format("%.0f ms", (load.lastTimeoutAt.get() - w[0]) / 1e6)));
        System.out.println("  成功的 GET                            " + pct(load.lats()));
        // 超时的命令被取消了，但还排在 Lettuce 的队列里；回复到了再丢掉，后面的命令不会错位
        String after = shared.sync().get(KEY);
        System.out.println("  之后再 GET 88 号：" + (after.equals(detail()) ? "拿到的是 88 号的详情，没有错位" : "错位了！" + after.substring(0, 40)));
        load.close();
        admin.close();
    }

    // ================================================================== 压测小工具
    /** conns 条连接，每条上 threadsPer 个线程不停地 GET 88 号；记录每次的开始时间和耗时。 */
    static class Load {
        final List<StatefulRedisConnection<String, String>> conns = new ArrayList<>();
        final int threadsPer;
        final List<long[]> starts = new ArrayList<>(), lats = new ArrayList<>();
        final int[] n;
        final AtomicLong timeouts = new AtomicLong(), lastTimeoutAt = new AtomicLong();

        Load(RedisClient client, int conns, int threadsPer) {
            for (int i = 0; i < conns; i++) this.conns.add(client.connect());
            this.threadsPer = threadsPer;
            this.n = new int[conns * threadsPer];
        }

        void run(long ms, Runnable event, long eventAtMs) throws Exception {
            int total = conns.size() * threadsPer;
            CountDownLatch ready = new CountDownLatch(total), go = new CountDownLatch(1), done = new CountDownLatch(total);
            for (int i = 0; i < total; i++) { starts.add(new long[1 << 14]); lats.add(new long[1 << 14]); }
            final long[] t0 = new long[1];
            for (int i = 0; i < total; i++) {
                final int id = i;
                final RedisCommands<String, String> cmd = conns.get(i / threadsPer).sync();
                Thread t = new Thread(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException e) { return; }
                    long end = t0[0] + ms * 1_000_000L;
                    long now;
                    while ((now = System.nanoTime()) < end) {
                        try {
                            cmd.get(KEY);
                            add(id, now, System.nanoTime() - now);
                        } catch (RedisCommandTimeoutException e) {
                            timeouts.incrementAndGet();
                            lastTimeoutAt.accumulateAndGet(System.nanoTime(), Math::max);
                        }
                    }
                    done.countDown();
                }, "biz-" + i);
                t.start();
            }
            ready.await();
            for (int i = 0; i < 300; i++) conns.get(i % conns.size()).sync().get(KEY);   // 预热
            t0[0] = System.nanoTime();
            go.countDown();
            if (event != null) {
                Thread.sleep(eventAtMs);
                event.run();
            }
            done.await();
        }

        void add(int id, long start, long lat) {
            long[] s = starts.get(id), l = lats.get(id);
            if (n[id] == s.length) {
                s = Arrays.copyOf(s, s.length * 2); l = Arrays.copyOf(l, l.length * 2);
                starts.set(id, s); lats.set(id, l);
            }
            s[n[id]] = start; l[n[id]] = lat; n[id]++;
        }

        long count() { long c = 0; for (int x : n) c += x; return c; }

        long[] lats() {
            long[] out = new long[(int) count()];
            int k = 0;
            for (int i = 0; i < n.length; i++) { System.arraycopy(lats.get(i), 0, out, k, n[i]); k += n[i]; }
            return out;
        }

        long[] latsStartedBetween(long from, long to) {
            long[] out = new long[(int) count()];
            int k = 0;
            for (int i = 0; i < n.length; i++)
                for (int j = 0; j < n[i]; j++)
                    if (starts.get(i)[j] >= from && starts.get(i)[j] <= to) out[k++] = lats.get(i)[j];
            return Arrays.copyOf(out, k);
        }

        void close() { for (StatefulRedisConnection<String, String> c : conns) c.close(); }
    }

    // ================================================================== 输出
    static String pct(long[] ns) {
        if (ns.length == 0) return "（没有样本）";
        long[] s = ns.clone();
        Arrays.sort(s);
        return String.format("p50 %s  p99 %s  max %s  (n=%d)", us(s[s.length / 2]),
                us(s[(int) Math.min(s.length - 1, Math.floor(s.length * 0.99))]), us(s[s.length - 1]), s.length);
    }

    static String us(long ns) {
        if (ns >= 10_000_000L) return String.format("%.0f ms", ns / 1e6);
        if (ns >= 1_000_000L) return String.format("%.2f ms", ns / 1e6);
        return String.format("%d µs", ns / 1000);
    }

    static String field(String info, String name) {
        for (String l : info.split("\r?\n")) if (l.startsWith(name + ":")) return l.substring(name.length() + 1).trim();
        return "?";
    }

    static String line(String info, String prefix) {
        for (String l : info.split("\r?\n")) if (l.startsWith(prefix)) return l.substring(prefix.length()).trim();
        return "?";
    }

    static String kv(String csv, String k) {
        for (String p : csv.split(",")) if (p.startsWith(k + "=")) return p.substring(k.length() + 1);
        return "?";
    }
}
