package labs.redis.redis_restart_failover;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 重启和切主以后：交易靶场商品缓存（product:&lt;id&gt;）和重建锁（lock:product:&lt;id&gt;）在 Redis 重启、主从切换时丢了什么。
 * <pre>
 *   restart   四种持久化配置各写 N 个商品，重启，看多久能读到 88 号、期间收到几次 LOADING
 *   failover  一主一从：正常复制多快；从库卡住时主库收了锁就宕机，提拔从库后另一个请求也拿到锁；min-replicas-to-write 能挡住多少
 *   wipe      主库不持久化，进程被杀后自动拉起，从库跟着被清空
 * </pre>
 * 容器由 Lab 自己用 docker 命令起停（DOCKER 环境变量可以是 "sudo docker"）。参数：-Dn=200000
 */
public class Lab {

    static final String IMAGE = "redis:8.10.3-alpine";
    static final String NET = "rf-net", REP = "rf-rep", M = "rf-m", R = "rf-r";
    static final int MP = 63791, RP = 63792;
    static final int N = Integer.getInteger("n", 200000);
    static final List<String> DOCKER = Arrays.asList(System.getenv().getOrDefault("DOCKER", "docker").split(" "));

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "all";
        clean();
        docker("network", "create", NET);   // 客户端走这个网络（端口映射在这上面）
        docker("network", "create", REP);   // 主从复制单独走这个网络，好在不影响客户端的情况下把主从断开
        try {
            if (which.equals("all") || which.equals("restart")) restart();
            if (which.equals("all") || which.equals("failover")) failover();
            if (which.equals("all") || which.equals("wipe")) wipe();
        } finally {
            clean();
        }
    }

    // ------------------------------------------------------------ restart
    static void restart() throws Exception {
        System.out.println("\n[restart] 每种配置写 " + N + " 个商品（648 字节 JSON，TTL 30 分钟），docker stop 再 docker start，探针每 2 ms 发一次 GET product:88");
        String[][] modes = {
                {"不持久化", "--save", "", "--appendonly", "no"},
                {"RDB（默认 save）", "--appendonly", "no"},
                {"AOF 纯命令", "--no-modules", "--save", "", "--appendonly", "yes", "--aof-use-rdb-preamble", "no"},   // 镜像自带的 RediSearch 要求 preamble=yes，否则拒绝启动
                {"AOF 混合（默认）", "--save", "", "--appendonly", "yes"},
        };
        for (String[] m : modes) {
            String name = m[0];
            List<String> flags = Arrays.asList(m).subList(1, m.length);
            startRedis(M, MP, flags);
            RedisClient c = RedisClient.create("redis://127.0.0.1:" + MP);
            long forkUs = -1;
            try (StatefulRedisConnection<String, String> conn = c.connect()) {
                RedisCommands<String, String> r = conn.sync();
                if (name.startsWith("RDB")) System.out.println("  默认 save = \"" + r.configGet("save").get("save") + "\"，appendonly = " + r.configGet("appendonly").get("appendonly"));
                load(conn, 1, N);
                if (name.startsWith("AOF")) {                           // 把增量日志合进 base 文件：纯命令 / RDB 格式
                    waitInfo(r, "persistence", "aof_rewrite_in_progress", "0");   // 写到 64 MB 时已经自动重写过一次
                    r.bgrewriteaof();
                    Thread.sleep(200);
                    waitInfo(r, "persistence", "aof_rewrite_in_progress", "0");
                    forkUs = Long.parseLong(info(r, "stats", "latest_fork_usec"));
                } else if (name.startsWith("RDB")) {
                    r.bgsave();
                    waitInfo(r, "persistence", "rdb_bgsave_in_progress", "0");
                    forkUs = Long.parseLong(info(r, "stats", "latest_fork_usec"));
                }
                System.out.printf("  %-14s used_memory %s，dbsize %d%s%n", name, mb(Long.parseLong(info(r, "memory", "used_memory"))),
                        r.dbsize(), forkUs >= 0 ? "，fork " + forkUs / 1000.0 + " ms" : "");
            } finally {
                c.shutdown();
            }
            long t = System.nanoTime();
            docker("stop", "-t", "60", M);
            long stopMs = ms(t);
            long t0 = System.nanoTime();
            docker("start", M);
            Probe p = probe(MP, t0, 60000);
            String files = docker("exec", M, "sh", "-c", "cd /data && find . -type f -exec du -k {} +");
            String loaded = grep(docker("logs", M), "DB loaded from");
            System.out.printf("  %-14s 停机 %d ms；文件 %s%n", name, stopMs, files.replace("\n", "  ").replace("./", ""));
            System.out.printf("  %-14s 从 docker start 算：第一次有回复 %d ms，LOADING %d 次，第一次读到 88 号 %s%n", name,
                    p.firstReply, p.loading, p.firstOk < 0 ? "（超时）" : p.firstOk + " ms" + (p.nil ? "，但回的是 nil：缓存全空" : ""));
            if (!loaded.isEmpty()) System.out.println("  " + spaces(14) + " 日志：" + loaded);
            docker("rm", "-fv", M);
        }
    }

    // ------------------------------------------------------------ failover
    static void failover() throws Exception {
        System.out.println("\n[failover] 一主（" + MP + "）一从（" + RP + "），都是默认配置");
        startPair(Collections.<String>emptyList());
        RedisClient cm = RedisClient.create("redis://127.0.0.1:" + MP), cr = RedisClient.create("redis://127.0.0.1:" + RP);
        StatefulRedisConnection<String, String> a = cm.connect(), rep = cr.connect();
        RedisCommands<String, String> m = a.sync(), r = rep.sync();
        try {
            // 1. 正常时复制有多快：SET 之后 WAIT 1，等从库回 ACK
            long[] lat = new long[1000];
            for (int i = 0; i < lat.length; i++) {
                m.set("product:" + (100000 + i), json(100000 + i));       // 别碰 88 号，后面要看它的回填丢没丢
                long t = System.nanoTime();
                long acked = m.waitForReplication(1, 1000);
                lat[i] = (System.nanoTime() - t) / 1000;
                if (acked != 1) System.out.println("  WAIT 没等到：" + acked);
            }
            Arrays.sort(lat);
            System.out.printf("  正常：SET 后 WAIT 1 等到从库确认，p50 %d µs，p99 %d µs，最慢 %d µs%n", lat[500], lat[990], lat[999]);

            // 2. 主从之间断网（客户端到主库、到从库都还通）。docker pause 不行：进程停了，内核照样把数据收进 socket 缓冲区
            docker("network", "disconnect", REP, R);
            String tokenA = "A-" + System.nanoTime();
            System.out.println("  主从之间断网后，请求 A 在主库上：");
            System.out.println("    SET lock:product:88 " + tokenA + " NX PX 30000 → " + m.set("lock:product:88", tokenA, SetArgs.Builder.nx().px(30000)));
            System.out.println("    SET product:88 …（回填）              → " + m.set("product:88", json(88), SetArgs.Builder.px(1800000)));
            long t = System.nanoTime();
            System.out.println("    WAIT 1 500                           → " + m.waitForReplication(1, 500) + "（" + ms(t) + " ms 后返回，0 = 没有从库确认）");
            Thread.sleep(1500);
            System.out.println("    主库看从库：" + grep(m.info("replication"), "slave0"));

            // 3. 主库宕机，提拔从库（Sentinel 做的就是这一步：对选中的从库发 REPLICAOF NO ONE）
            docker("kill", M);
            t = System.nanoTime();
            r.replicaofNoOne();
            System.out.println("  kill 主库，提拔从库（REPLICAOF NO ONE，" + ms(t) + " ms）。新主库上：");
            System.out.println("    GET product:88                       → " + r.get("product:88") + "（回填丢了，下一个请求要再查一次库）");
            String tokenB = "B-" + System.nanoTime();
            System.out.println("    请求 B：SET lock:product:88 " + tokenB + " NX PX 30000 → " + r.set("lock:product:88", tokenB, SetArgs.Builder.nx().px(30000)));
            System.out.println("    A 以为自己还拿着锁，B 也拿到了锁：两个请求同时在重建 88 号");
            Long unlockA = r.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    ScriptOutputType.INTEGER, new String[]{"lock:product:88"}, tokenA);
            System.out.println("    A 查完库用 Lua 解锁（比对 token）    → " + unlockA + "（没误删 B 的锁）；GET lock → " + r.get("lock:product:88"));
        } finally {
            a.close(); rep.close(); cm.shutdown(); cr.shutdown();
            docker("rm", "-fv", M, R);
        }

        // 4. min-replicas-to-write：好从库不够就拒写
        System.out.println("\n  主库加 min-replicas-to-write 1、min-replicas-max-lag 2（默认 0 和 10）：");
        startPair(Arrays.asList("--min-replicas-to-write", "1", "--min-replicas-max-lag", "2"));
        cm = RedisClient.create("redis://127.0.0.1:" + MP);
        try (StatefulRedisConnection<String, String> conn = cm.connect()) {
            RedisCommands<String, String> mm = conn.sync();
            System.out.println("    从库正常：SET → " + mm.set("product:1", json(1)));
            docker("network", "disconnect", REP, R);
            long t0 = System.nanoTime(), firstReject = -1;
            int accepted = 0;
            while (ms(t0) < 6000) {
                try {
                    mm.set("product:" + (100 + accepted), json(1));
                    accepted++;
                } catch (RedisCommandExecutionException e) {
                    if (firstReject < 0) {
                        firstReject = ms(t0);
                        System.out.println("    主从断网后 " + firstReject + " ms 第一次被拒：" + e.getMessage());
                        System.out.println("    在那之前主库又收了 " + accepted + " 次写（每 20 ms 一次），这些写从库都没有");
                    }
                }
                Thread.sleep(20);
            }
            System.out.println("    读照常：GET product:1 → " + (mm.get("product:1") != null ? "命中" : "nil"));
            docker("network", "connect", REP, R);
            long t1 = System.nanoTime();
            while (true) {
                try { mm.set("product:2", json(2)); break; } catch (RedisCommandExecutionException e) { Thread.sleep(20); }
            }
            System.out.println("    网络恢复后 " + ms(t1) + " ms 写入恢复");
        } finally {
            cm.shutdown();
            docker("rm", "-fv", M, R);
        }
    }

    // ------------------------------------------------------------ wipe
    static void wipe() throws Exception {
        System.out.println("\n[wipe] 主库 --save \"\" --appendonly no（不持久化），从库默认；主库被 kill 后马上拉起（相当于进程守护自动重启）");
        startPair(Arrays.asList("--save", "", "--appendonly", "no"));
        RedisClient cm = RedisClient.create("redis://127.0.0.1:" + MP), cr = RedisClient.create("redis://127.0.0.1:" + RP);
        try (StatefulRedisConnection<String, String> a = cm.connect(); StatefulRedisConnection<String, String> rep = cr.connect()) {
            load(a, 1, 10000);
            a.sync().waitForReplication(1, 5000);
            RedisCommands<String, String> r = rep.sync();
            System.out.println("  主库 dbsize " + a.sync().dbsize() + "，从库 dbsize " + r.dbsize());
            docker("kill", M);
            Thread.sleep(300);
            System.out.println("  主库被 kill，300 ms 后：从库 dbsize " + r.dbsize() + "，还能读 product:88 → " + (r.get("product:88") != null ? "命中" : "nil"));
            long t0 = System.nanoTime();
            docker("start", M);
            long last = -1;
            while (ms(t0) < 15000) {
                long n;
                try { n = r.dbsize(); } catch (RedisCommandExecutionException e) { n = -2; }
                if (n != last) { System.out.println("    主库拉起后 " + ms(t0) + " ms：从库 dbsize " + (n == -2 ? "（LOADING）" : n)); last = n; }
                if (n == 0 && ms(t0) > 2000) break;
                Thread.sleep(20);
            }
            System.out.println("  从库日志：");
            for (String line : docker("logs", R).split("\n"))
                if (line.contains("Full resync") || line.contains("Flushing old data") || line.contains("Partial resynchronization not possible") || line.contains("Loading DB in memory"))
                    System.out.println("    " + line.replaceAll("^\\d+:\\S+ ", ""));
        } finally {
            cm.shutdown(); cr.shutdown();
            docker("rm", "-fv", M, R);
        }
    }

    // ------------------------------------------------------------ helpers
    static void startRedis(String name, int port, List<String> flags) throws Exception {
        List<String> cmd = new ArrayList<String>(Arrays.asList("run", "-d", "--name", name, "--network", NET, "-p", "127.0.0.1:" + port + ":6379"));
        if (!flags.isEmpty() && flags.get(0).equals("--no-modules")) {   // 绕过镜像入口脚本，不加载 bf/search/timeseries/json 模块
            cmd.addAll(Arrays.asList("--entrypoint", "redis-server", IMAGE));
            cmd.addAll(flags.subList(1, flags.size()));
        } else {
            cmd.addAll(Arrays.asList(IMAGE, "redis-server"));
            cmd.addAll(flags);
        }
        docker(cmd.toArray(new String[0]));
        waitPing(port);
    }

    /** 主从都接在 NET（端口映射）和 REP（复制）两个网络上，从库按 REP 上的 IP 连主库 */
    static void startPair(List<String> masterFlags) throws Exception {
        startRedis(M, MP, masterFlags);
        startRedis(R, RP, Collections.<String>emptyList());
        docker("network", "connect", REP, M);
        docker("network", "connect", REP, R);
        String ip = docker("inspect", "-f", "{{(index .NetworkSettings.Networks \"" + REP + "\").IPAddress}}", M);
        RedisClient c = RedisClient.create("redis://127.0.0.1:" + RP);
        try (StatefulRedisConnection<String, String> conn = c.connect()) {
            conn.sync().replicaof(ip, 6379);
            waitInfo(conn.sync(), "replication", "master_link_status", "up");
        } finally {
            c.shutdown();
        }
    }

    /** 用流水线把 product:from..to 写进去，每 1000 条 flush 一次 */
    static void load(StatefulRedisConnection<String, String> conn, int from, int to) throws Exception {
        RedisAsyncCommands<String, String> as = conn.async();
        conn.setAutoFlushCommands(false);
        List<RedisFuture<String>> fs = new ArrayList<RedisFuture<String>>();
        for (int i = from; i <= to; i++) {
            fs.add(as.set("product:" + i, json(i), SetArgs.Builder.px(1800000)));
            if (fs.size() == 1000 || i == to) {
                conn.flushCommands();
                for (RedisFuture<String> f : fs) f.get(10, TimeUnit.SECONDS);
                fs.clear();
            }
        }
        conn.setAutoFlushCommands(true);
    }

    static class Probe { long firstReply = -1, firstOk = -1, loading; boolean nil; }

    /** 裸 socket 发 GET product:88：不经过客户端库，看 Redis 本身回什么 */
    static Probe probe(int port, long t0, long timeoutMs) throws InterruptedException {
        Probe p = new Probe();
        byte[] get = "*2\r\n$3\r\nGET\r\n$10\r\nproduct:88\r\n".getBytes(StandardCharsets.US_ASCII);
        while (ms(t0) < timeoutMs) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                s.setSoTimeout(5000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                while (ms(t0) < timeoutMs) {
                    out.write(get);
                    out.flush();
                    String line = readLine(in);
                    if (line == null) throw new IOException("closed");
                    if (p.firstReply < 0) p.firstReply = ms(t0);
                    if (line.startsWith("-LOADING")) { p.loading++; Thread.sleep(2); continue; }
                    if (line.startsWith("$")) {
                        int len = Integer.parseInt(line.substring(1));
                        if (len >= 0) { for (int i = 0; i < len + 2; i++) in.read(); }
                        p.nil = len < 0;
                        p.firstOk = ms(t0);
                        return p;
                    }
                    System.out.println("    探针收到：" + line);
                    Thread.sleep(2);
                }
            } catch (IOException e) {
                Thread.sleep(5);
            }
        }
        return p;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') { in.read(); return sb.toString(); }
            sb.append((char) c);
        }
        return null;
    }

    static void waitPing(int port) throws Exception {
        long t0 = System.nanoTime();
        while (ms(t0) < 20000) {
            RedisClient c = RedisClient.create("redis://127.0.0.1:" + port);
            try (StatefulRedisConnection<String, String> conn = c.connect()) {
                if ("PONG".equals(conn.sync().ping())) return;
            } catch (Exception e) {
                Thread.sleep(100);
            } finally {
                c.shutdown(0, 0, TimeUnit.MILLISECONDS);
            }
        }
        throw new IllegalStateException("redis on " + port + " not up");
    }

    static void waitInfo(RedisCommands<String, String> r, String section, String field, String want) throws InterruptedException {
        long t0 = System.nanoTime();
        while (!want.equals(info(r, section, field))) {
            if (ms(t0) > 120000) throw new IllegalStateException(field + " != " + want);
            Thread.sleep(50);
        }
    }

    static String info(RedisCommands<String, String> r, String section, String field) {
        for (String line : r.info(section).split("\r?\n"))
            if (line.startsWith(field + ":")) return line.substring(field.length() + 1).trim();
        return "";
    }

    static String grep(String text, String word) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n"))
            if (line.contains(word)) sb.append(sb.length() > 0 ? " | " : "").append(line.replaceAll("^\\d+:\\S+ \\d+ \\w+ \\d+ [\\d:.]+ ", ""));
        return sb.toString();
    }

    static String docker(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<String>(DOCKER);
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor();
        return sb.toString().trim();
    }

    static void clean() throws Exception {
        docker("rm", "-fv", M, R);
        docker("network", "rm", NET, REP);
    }

    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }
    static String mb(long b) { return String.format("%.1f MB", b / 1048576.0); }
    static String spaces(int n) { char[] c = new char[n]; Arrays.fill(c, ' '); return new String(c); }

    static String json(long id) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(id).append(",\"name\":\"机械键盘 K").append(id)
                .append("\",\"price\":\"399.00\",\"stock\":1200,\"detail\":\"");
        java.util.Random rnd = new java.util.Random(id);              // 随机字母：不让 RDB 的 LZF 压缩占便宜
        while (sb.length() < 640) sb.append((char) ('a' + rnd.nextInt(26)));
        return sb.append("\"}").toString();
    }
}
