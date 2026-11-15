package labs.arch.multi_active_dr;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主库挂掉、从库顶上时，已经告诉客户端“成功”的写入还在不在（RPO）。
 * 两个 Redis 容器 dr-a（主，端口 63801）和 dr-b（从，63802）在网络 dr-net 上，模拟两个机房。
 * dr-b 用专门的 ACL 用户 repl 复制 dr-a。“专线断了”= 在 dr-a 上禁用 repl 并踢掉复制连接：dr-b 重连时认证失败，复制停住，
 * 而 dr-a 照常接受业务写入（不碰网络，所以主机上的程序始终连得上两个容器）。
 * 时间线：正常写 1 秒 → 断开 dr-b 的网络（机房间链路断了）→ 再写 2 秒 → 杀掉 dr-a（机房 A 断电）→ dr-b 提升为主。
 * 两种写法：
 *   async：INCR 返回就算成功（Redis 默认，主从异步复制）
 *   wait ：INCR 后 WAIT 1 200，至少 1 个从库确认才算成功，超时就告诉客户端失败
 * 需要 docker；本机不在 docker 组里就设 DOCKER="sudo docker"。
 */
public class FailoverLab {

    static final String DOCKER = System.getenv().getOrDefault("DOCKER", "docker");
    static final String IMAGE = "redis:8.10.3-alpine";

    public static void main(String[] args) throws Exception {
        for (String mode : new String[]{"async", "wait"}) run(mode);
        clean();
    }

    static void run(String mode) throws Exception {
        clean();
        sh("network create dr-net");
        sh("run -d --name dr-a --network dr-net -p 127.0.0.1:63801:6379 " + IMAGE
                + " redis-server --user default on nopass ~* &* +@all --user repl on >replpw +psync +replconf +ping");
        sh("run -d --name dr-b --network dr-net -p 127.0.0.1:63802:6379 " + IMAGE
                + " redis-server --replicaof dr-a 6379 --masteruser repl --masterauth replpw");
        RedisClient client = RedisClient.create();
        StatefulRedisConnection<String, String> a = connectRetry(client, 63801);
        StatefulRedisConnection<String, String> b = connectRetry(client, 63802);
        RedisCommands<String, String> ra = a.sync(), rb = b.sync();
        while (!rb.info("replication").contains("master_link_status:up")) Thread.sleep(100);
        a.setTimeout(Duration.ofSeconds(1));

        AtomicLong acked = new AtomicLong(), failed = new AtomicLong(), attempts = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                attempts.incrementAndGet();
                try {
                    long v = ra.incr("seq");
                    if (mode.equals("wait")) {
                        long n = ra.waitForReplication(1, 200);
                        if (n < 1) { failed.incrementAndGet(); continue; }   // 告诉客户端：没写成功
                    }
                    acked.set(v);                                            // 告诉客户端：成功
                } catch (Exception e) {
                    failed.incrementAndGet();
                    try { Thread.sleep(50); } catch (InterruptedException ie) { return; }
                }
            }
        });
        writer.start();
        Thread.sleep(1000);
        long beforeCut = acked.get();
        sh("exec dr-a redis-cli ACL SETUSER repl off");     // 机房间专线断了：复制用户禁用
        sh("exec dr-a redis-cli CLIENT KILL TYPE replica");
        Thread.sleep(2000);
        long t0 = System.nanoTime();
        sh("kill dr-a");                                    // 机房 A 断电
        Thread.sleep(300);
        stop.set(true);
        writer.join(3000);
        rb.replicaofNoOne();                                // dr-b 提升为主
        long promoteMs = (System.nanoTime() - t0) / 1_000_000;
        long onB = Long.parseLong(rb.get("seq"));
        long lost = acked.get() - onB;
        System.out.printf("%-5s 断网前已确认 %d；客户端最后收到“成功”的是第 %d 次；断网后被告知失败 %d 次；新主 dr-b 上 seq = %d → 已确认却丢了 %d 次写入%n",
                mode, beforeCut, acked.get(), failed.get(), onB, Math.max(0, lost));
        b.close();
        try { a.close(); } catch (Exception ignored) { }
        client.shutdown();
    }

    static StatefulRedisConnection<String, String> connectRetry(RedisClient c, int port) throws InterruptedException {
        for (int i = 0; ; i++) {
            try { return c.connect(RedisURI.create("127.0.0.1", port)); }
            catch (Exception e) { if (i > 50) throw e; Thread.sleep(200); }
        }
    }

    static void clean() {
        try { sh("rm -fv dr-a dr-b"); } catch (Exception ignored) { }
        try { sh("network rm dr-net"); } catch (Exception ignored) { }
    }

    static void sh(String args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(Arrays.asList(DOCKER.split(" ")));
        cmd.addAll(Arrays.asList(args.split(" ")));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = readAll(p.getInputStream());
        if (p.waitFor() != 0 && !args.startsWith("rm") && !args.startsWith("network rm"))
            throw new IllegalStateException(args + ": " + new String(out));
    }

    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
