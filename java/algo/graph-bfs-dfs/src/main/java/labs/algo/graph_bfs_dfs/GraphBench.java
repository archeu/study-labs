package labs.algo.graph_bfs_dfs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 1 表示法的内存：20 万个账户、100 万条边，HashMap<Long, List<Long>> vs CSR 两个 int 数组（JOL 计）
 * 2 BFS 耗时：同一张图两种表示
 * 3 坑：忘了 visited、出队才标记导致重复入队、长链上递归 DFS
 * CP=algo/graph-bfs-dfs/target/classes:$HOME/.m2/repository/org/openjdk/jol/jol-core/0.17/jol-core-0.17.jar
 * java -Xmx2g -cp $CP labs.algo.graph_bfs_dfs.GraphBench
 */
public class GraphBench {
    public static void main(String[] args) {
        int n = 200_000, m = 1_000_000;
        Random r = new Random(3);
        int[] ea = new int[m], eb = new int[m];
        for (int i = 0; i < m; i++) { ea[i] = r.nextInt(n); eb[i] = r.nextInt(n); }

        AccountGraph g = new AccountGraph();
        for (int i = 0; i < m; i++) g.link(ea[i], eb[i]);
        // CSR：off[u]..off[u+1] 是 u 的邻居在 to[] 里的位置
        int[] deg = new int[n + 1];
        for (int i = 0; i < m; i++) { deg[ea[i]]++; deg[eb[i]]++; }
        int[] off = new int[n + 1];
        for (int i = 0; i < n; i++) off[i + 1] = off[i] + deg[i];          // 第 12 课的前缀和
        int[] to = new int[2 * m], pos = off.clone();
        for (int i = 0; i < m; i++) { to[pos[ea[i]]++] = eb[i]; to[pos[eb[i]]++] = ea[i]; }

        long mapBytes = org.openjdk.jol.info.GraphLayout.parseInstance(g.adj).totalSize();
        long csrBytes = 4L * off.length + 4L * to.length;
        System.out.printf("表示法：%,d 个账户、%,d 条边%n  HashMap<Long, List<Long>>  %.0f MB（JOL）%n  CSR 两个 int 数组           %.1f MB%n  邻接矩阵（按位）             %.1f GB%n%n",
                n, m, mapBytes / 1e6, csrBytes / 1e6, (double) n * n / 8 / 1e9);

        for (int round = 1; round <= 3; round++) {
            long t0 = System.nanoTime();
            int reached1 = g.bfs(0, null, false).size();
            long t1 = System.nanoTime() - t0;
            t0 = System.nanoTime();
            int[] dist = new int[n]; java.util.Arrays.fill(dist, -1);
            int[] q = new int[n]; int h = 0, t = 0; q[t++] = 0; dist[0] = 0;
            while (h < t) { int u = q[h++]; for (int k = off[u]; k < off[u + 1]; k++) { int v = to[k]; if (dist[v] < 0) { dist[v] = dist[u] + 1; q[t++] = v; } } }
            long t2 = System.nanoTime() - t0;
            System.out.printf("BFS 第 %d 轮：HashMap 版 %d ms（到达 %,d 个），CSR 版 %.1f ms（到达 %,d 个）%n", round, t1 / 1_000_000, reached1, t2 / 1e6, t);
        }

        System.out.println("\n== 坑");
        AccountGraph tri = new AccountGraph(); tri.link(1, 2); tri.link(2, 3); tri.link(3, 1);
        ArrayDeque<Long> q = new ArrayDeque<>(); q.offer(1L); int pops = 0;
        while (!q.isEmpty() && pops < 1_000) { long u = q.poll(); pops++; for (long v : tri.nb(u)) q.offer(v); }
        System.out.println("坑 1 三角形上不记 visited：出队 " + pops + " 次还没停（只有 3 个账户），队列里还有 " + q.size() + " 个");

        int pushes = 0; Set<Integer> seen = new HashSet<>(); ArrayDeque<Integer> q2 = new ArrayDeque<>(); q2.offer(0);
        while (!q2.isEmpty()) { int u = q2.poll(); if (!seen.add(u)) continue; for (int k = off[u]; k < off[u + 1]; k++) { if (!seen.contains(to[k])) { q2.offer(to[k]); pushes++; } } }
        System.out.printf("坑 2 出队时才标记：入队 %,d 次（入队时标记只要 %,d 次）%n", pushes, seen.size() - 1);

        AccountGraph chain = new AccountGraph();
        for (int i = 0; i < 100_000; i++) chain.link(i, i + 1);              // 一条 10 万个账户的转账链
        try { chain.dfsRec(0, new HashSet<Long>(), new ArrayList<Long>()); System.out.println("坑 3 递归 DFS 没溢出"); }
        catch (StackOverflowError e) { System.out.println("坑 3 10 万个账户的转账链：递归 DFS StackOverflowError，显式栈 DFS 访问 " + chain.dfs(0).size() + " 个"); }
    }
}
