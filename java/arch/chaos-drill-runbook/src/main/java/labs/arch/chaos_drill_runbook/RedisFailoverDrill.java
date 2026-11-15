package labs.arch.chaos_drill_runbook;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次 Redis 主节点宕机演练（全景 J12 第 11 站），按演练的五步走：
 *   1 稳态假设：写入成功率 ≥ 99%，每秒写入数稳定
 *   2 注入故障：docker kill 主节点
 *   3 观察：客户端从第一次失败到恢复写入用了多久（客户端看到的 RTO），丢了多少已确认写入（RPO）
 *   4 验证预案：不可用期间的请求都被“降级”（快速失败）了，没有卡住
 *   5 恢复：旧主重启，自动变成新主的从库
 * 拓扑：1 主 2 从 + 3 个哨兵（quorum 2），都在网络 ch-net 上；客户端用容器 IP 直连（Linux 主机能直接访问容器网段）。
 * 参数：sentinel down-after-milliseconds 分别为 30000（示例配置里的值）和 5000，对比 RTO。
 * 需要 docker；本机不在 docker 组里就设 DOCKER="sudo docker"。
 */
public class RedisFailoverDrill {

    static final String DOCKER = System.getenv().getOrDefault("DOCKER", "docker");
    static final String IMAGE = "redis:8.10.3-alpine";

    public static void main(String[] args) throws Exception {
        int[] downAfters = args.length > 0 ? Arrays.stream(args).mapToInt(Integer::parseInt).toArray() : new int[]{30000, 5000};
        try {
            for (int d : downAfters) drill(d);
        } finally {
            clean();
        }
    }

    static void drill(int downAfterMs) throws Exception {
        clean();
        sh("network", "create", "ch-net");
        sh("run", "-d", "--name", "ch-m", "--network", "ch-net", IMAGE);
        String mip = ip("ch-m");
        for (String r : new String[]{"ch-r1", "ch-r2"})
            sh("run", "-d", "--name", r, "--network", "ch-net", IMAGE, "redis-server", "--replicaof", mip, "6379");
        String conf = "port 26379\\nsentinel monitor mymaster " + mip + " 6379 2\\n"
                + "sentinel down-after-milliseconds mymaster " + downAfterMs + "\\nsentinel failover-timeout mymaster 60000\\n";
        List<String> sips = new ArrayList<>();
        for (String s : new String[]{"ch-s1", "ch-s2", "ch-s3"}) {
            sh("run", "-d", "--name", s, "--network", "ch-net", IMAGE, "sh", "-c",
                    "printf '" + conf + "' > /tmp/s.conf && exec redis-sentinel /tmp/s.conf");
            sips.add(ip(s));
        }
        RedisURI.Builder b = RedisURI.builder().withSentinelMasterId("mymaster").withTimeout(Duration.ofMillis(500));
        for (String s : sips) b.withSentinel(s, 26379);
        RedisClient client = RedisClient.create(b.build());
        Thread.sleep(3000);   // 等哨兵互相发现、从库同步完
        StatefulRedisConnection<String, String> conn = client.connect();
        conn.setTimeout(Duration.ofMillis(500));
        RedisCommands<String, String> r = conn.sync();

        AtomicLong ok = new AtomicLong(), fail = new AtomicLong(), lastAcked = new AtomicLong(), slowest = new AtomicLong();
        AtomicLong firstFailNs = new AtomicLong(), recoverNs = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean(), killed = new AtomicBoolean();
        Thread w = new Thread(() -> {
            while (!stop.get()) {
                long t = System.nanoTime();
                try {
                    long v = r.incr("seq");
                    lastAcked.set(Math.max(lastAcked.get(), v));
                    ok.incrementAndGet();
                    if (firstFailNs.get() != 0 && recoverNs.get() == 0) recoverNs.set(System.nanoTime());
                } catch (Exception e) {
                    fail.incrementAndGet();                         // 预案：熔断 / 降级，立刻回“稍后再试”
                    if (killed.get()) firstFailNs.compareAndSet(0, System.nanoTime());
                    sleepQuiet(20);
                }
                slowest.accumulateAndGet((System.nanoTime() - t) / 1_000_000, Math::max);
                sleepQuiet(2);
            }
        });
        // 1 稳态
        w.start();
        Thread.sleep(2000);
        long okSteady = ok.get(), failSteady = fail.get();
        System.out.printf("%n== down-after-milliseconds = %d ==%n1 稳态：2 秒写入成功 %d 次，失败 %d 次，主节点 %s%n",
                downAfterMs, okSteady, failSteady, mip);
        // 2 注入
        long tKill = System.nanoTime();
        sh("kill", "ch-m");
        killed.set(true);
        System.out.println("2 注入：docker kill ch-m");
        // 3 观察
        while (recoverNs.get() == 0 && (System.nanoTime() - tKill) < 120_000_000_000L) Thread.sleep(50);
        Thread.sleep(1000);
        stop.set(true); w.join(2000);
        double rto = (recoverNs.get() - tKill) / 1e9;
        String newMaster = r.info("server").contains("redis_version") ? r.role().get(0).toString() : "?";
        long now = Long.parseLong(r.get("seq"));
        System.out.printf("3 观察：杀主后 %.1f 秒恢复写入；期间失败 %d 次；单次请求最慢 %d ms（超时设 500 ms）%n",
                rto, fail.get() - failSteady, slowest.get());
        System.out.printf("        新主上 seq = %d，客户端确认过的最大值 %d → 丢失已确认写入 %d 次%n",
                now, lastAcked.get(), Math.max(0, lastAcked.get() - now));
        System.out.printf("4 验证预案：不可用期间每个请求最多等 %d ms 就拿到结果（降级），没有请求卡住%n", slowest.get());
        // 5 恢复：旧主重启，在被哨兵降级之前，有人直连它写了一条
        sh("start", "ch-m");
        long tStart = System.nanoTime();
        String w1 = "";
        for (int i = 0; i < 50 && !w1.contains("OK"); i++) { Thread.sleep(100); w1 = shOut("exec", "ch-m", "redis-cli", "SET", "orphan", "written-to-old-master"); }
        String role = "";
        while (!role.contains("role:slave") && System.nanoTime() - tStart < 60_000_000_000L) {
            Thread.sleep(200);
            role = shOut("exec", "ch-m", "redis-cli", "info", "replication");
        }
        double conv = (System.nanoTime() - tStart) / 1e9;
        String before = shOut("exec", "ch-m", "redis-cli", "GET", "orphan").trim();
        for (int i = 0; i < 150 && !role.contains("master_link_status:up"); i++) {
            Thread.sleep(200);
            role = shOut("exec", "ch-m", "redis-cli", "info", "replication");
        }
        double synced = (System.nanoTime() - tStart) / 1e9;
        System.out.printf("5 恢复：旧主重启 %.1f 秒后被降为从库（此刻 orphan = %s），%.1f 秒后和新主同步完成%n",
                conv, before.isEmpty() ? "(没了)" : before, synced);
        String onOld = shOut("exec", "ch-m", "redis-cli", "GET", "orphan").trim();
        String onNew = r.get("orphan");
        System.out.printf("        同步完成后，那段时间直连旧主写的 orphan：旧主上 %s，新主上 %s%n",
                onOld.isEmpty() ? "(没了)" : onOld, onNew == null ? "(没有)" : onNew);
        conn.close();
        client.shutdown();
    }

    static void sleepQuiet(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static String ip(String name) throws Exception {
        return shOut("inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name).trim();
    }

    static void clean() {
        for (String n : new String[]{"ch-m", "ch-r1", "ch-r2", "ch-s1", "ch-s2", "ch-s3"}) try { shOut("rm", "-fv", n); } catch (Exception ignored) { }
        try { shOut("network", "rm", "ch-net"); } catch (Exception ignored) { }
    }

    static void sh(String... args) throws Exception { shOut(args); }

    static String shOut(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(Arrays.asList(DOCKER.split(" ")));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = p.getInputStream().read(buf)) > 0) bo.write(buf, 0, n);
        int code = p.waitFor();
        String out = bo.toString("UTF-8");
        if (code != 0 && !"rm".equals(args[0]) && !"network".equals(args[0])) throw new IllegalStateException(String.join(" ", args) + ": " + out);
        return out;
    }
}
