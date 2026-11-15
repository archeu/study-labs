package labs.arch.raft_consensus;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 用 SOFAJRaft 1.3.14 起 3 个独立进程（tc-1、tc-2、tc-3），量四件事：
 *   1. 冷启动多久选出 leader；
 *   2. leader 每 20 ms 提交一条记录，过半提交要多久（本机回环网络，日志 fsync）；
 *   3. kill -9 leader：多久选出新 leader、多久写不进；和 SIGTERM 优雅停机对比；
 *   4. 杀掉两个 follower：leader 多久发现自己凑不够多数派、主动退位。
 *
 * 用法：java -cp <classpath> labs.arch.raft_consensus.JRaftLab [数据目录，默认 target/jraft-data] [轮数，默认 3]
 */
public class JRaftLab {
    static final String[] IDS = {"tc-1", "tc-2", "tc-3"};
    static final Map<String, Process> procs = new HashMap<String, Process>();
    static final BlockingQueue<String[]> events = new LinkedBlockingQueue<String[]>();
    static File dataDir;

    static void start(final String id) throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx128m", "-cp", System.getProperty("java.class.path"),
                JRaftNode.class.getName(), id, dataDir.getPath());
        pb.redirectError(new File(dataDir, id + ".err"));
        final Process p = pb.start();
        procs.put(id, p);
        Thread t = new Thread(new Runnable() { public void run() {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (line.startsWith("EV ")) events.add(line.substring(3).split(" "));
            } catch (Exception ignored) { }
        }});
        t.setDaemon(true); t.start();
    }

    /** 等某类事件（可加条件），返回事件；超时返回 null。顺手记下所有 COMMIT。 */
    static final List<String[]> commits = new ArrayList<String[]>();
    static String[] waitFor(String type, String notFrom, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            String[] e = events.poll(left, TimeUnit.MILLISECONDS);
            if (e == null) return null;
            if (e[1].equals("COMMIT")) commits.add(e);
            if (e[1].equals(type) && (notFrom == null || !e[2].equals(notFrom))) return e;
        }
    }
    static void drain(long ms) throws InterruptedException { waitFor("__never__", null, ms); }
    static String currentLeader = null;

    static void deleteDir(File f) { File[] fs = f.listFiles(); if (fs != null) for (File c : fs) deleteDir(c); f.delete(); }

    public static void main(String[] args) throws Exception {
        dataDir = new File(args.length > 0 ? args[0] : "target/jraft-data");
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        deleteDir(dataDir); dataDir.mkdirs();
        System.out.println("SOFAJRaft 1.3.14 · 3 个进程 · 127.0.0.1:18081~18083 · electionTimeoutMs=1000 · 数据目录 " + dataDir);

        // 1. 冷启动
        long t0 = System.currentTimeMillis();
        for (String id : IDS) start(id);
        String[] up = null; int ups = 0;
        while (ups < 3) { String[] e = waitFor("UP", null, 30000); if (e == null) throw new IllegalStateException("节点没起来，看 " + dataDir + "/*.err"); ups++; up = e; }
        long allUp = Long.parseLong(up[0]);
        String[] l = waitFor("LEADER", null, 10000);
        currentLeader = l[2];
        System.out.printf("%n[1] 冷启动：3 个 JVM 起好用了 %d ms；最后一个起好之后 %d ms，%s 当选，任期 %s%n",
                allUp - t0, Long.parseLong(l[0]) - allUp, l[2], l[3]);

        // 2. 过半提交耗时
        commits.clear(); drain(3000);
        List<Long> us = new ArrayList<Long>();
        for (String[] c : commits) us.add(Long.parseLong(c[5]));
        Collections.sort(us);
        System.out.printf("[2] leader %s 每 20 ms 提交一条，3 秒共 %d 条：过半提交 p50 = %.2f ms，p99 = %.2f ms，最大 %.2f ms%n",
                currentLeader, us.size(), us.get(us.size() / 2) / 1000.0, us.get(us.size() * 99 / 100) / 1000.0, us.get(us.size() - 1) / 1000.0);

        // 3. 杀 leader
        for (boolean graceful : new boolean[]{false, true}) {
            System.out.println("\n[3] " + (graceful ? "SIGTERM 优雅停机" : "kill -9") + " 当前 leader，" + rounds + " 轮：");
            for (int i = 1; i <= rounds; i++) {
                String victim = currentLeader;
                commits.clear(); drain(300);
                long lastCommitBefore = commits.isEmpty() ? System.currentTimeMillis() : Long.parseLong(commits.get(commits.size() - 1)[0]);
                long kill = System.currentTimeMillis();
                if (graceful) procs.get(victim).destroy(); else procs.get(victim).destroyForcibly();
                String[] nl = waitFor("LEADER", victim, 15000);
                if (nl == null) { System.out.println("  第 " + i + " 轮：15 秒内没选出新 leader"); return; }
                String[] fc = waitFor("COMMIT", victim, 5000);
                procs.get(victim).waitFor();
                currentLeader = nl[2];
                System.out.printf("  第 %d 轮：杀 %s → %4d ms 后 %s 当选（任期 %s）；写不进的窗口 %4d ms%n",
                        i, victim, Long.parseLong(nl[0]) - kill, nl[2], nl[3],
                        (fc == null ? -1 : Long.parseLong(fc[0]) - lastCommitBefore));
                start(victim);                                              // 用原来的数据目录重启，追上日志
                waitFor("UP", null, 30000); drain(2500);
            }
        }

        // 4. 杀两个 follower
        List<String> followers = new ArrayList<String>();
        for (String id : IDS) if (!id.equals(currentLeader)) followers.add(id);
        commits.clear(); drain(300);
        long kill = System.currentTimeMillis();
        for (String f : followers) procs.get(f).destroyForcibly();
        String[] sd = waitFor("STEPDOWN", null, 10000);
        int okAfter = 0, failAfter = 0;
        for (String[] c : commits) if (Long.parseLong(c[0]) > kill + 50) okAfter++;
        System.out.printf("%n[4] kill -9 两个 follower %s：leader %s 在 %s ms 后退位（%s）；之后提交成功 %d 条%n",
                followers, currentLeader, sd == null ? "10000+" : String.valueOf(Long.parseLong(sd[0]) - kill),
                sd == null ? "-" : String.join(" ", Arrays.copyOfRange(sd, 3, sd.length)), okAfter);

        for (Process p : procs.values()) p.destroyForcibly().waitFor();
        System.out.println("\n全部进程已结束。数据目录 " + dataDir + " 可以删掉。");
    }
}
