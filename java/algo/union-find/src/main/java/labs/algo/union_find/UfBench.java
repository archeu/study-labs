package labs.algo.union_find;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 1 四种组合（裸 / 按大小 / 路径压缩 / 两个都开），最坏输入：一路把新账户并到一条链上，再查 n 次
 * 2 随机关联边流：100 万账户、100 万条边，边来一条合并一条、再问一次“是不是一伙”；对比每次都 BFS
 * 3 坑：不找根直接改 parent
 * java -Xmx1g -cp algo/union-find/target/classes labs.algo.union_find.UfBench
 */
public class UfBench {
    public static void main(String[] args) {
        int n = 30_000;
        System.out.println("== 最坏输入：union(i, i+1) 依次把账户并起来，再查每个账户 · n = " + n);
        String[] names = {"裸的", "只按大小合并", "只路径压缩", "按大小 + 路径压缩"};
        boolean[][] opt = {{false, false}, {true, false}, {false, true}, {true, true}};
        for (int k = 0; k < 4; k++) {
            UnionFind uf = new UnionFind(n, opt[k][0], opt[k][1]);
            long t0 = System.nanoTime();
            for (int i = 0; i + 1 < n; i++) uf.union(i + 1, i);    // 每次都把旧的那一大伙挂到新账户下面（裸的版本会排成一条链）
            int maxDepth = 0; for (int i = 0; i < n; i += 97) maxDepth = Math.max(maxDepth, uf.depth(i));
            uf.steps = 0;
            for (int i = 0; i < n; i++) uf.find(i);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%-10s 合并后最深 %,7d 层   查 %,d 次共走 %,13d 步   %,6d ms%n", names[k], maxDepth, n, uf.steps, ms);
        }

        System.out.println("\n== 关联边流：100 万账户、边一条条来，每来一条合并并问一次");
        int N = 1_000_000, M = 1_000_000;
        Random r = new Random(9);
        int[] a = new int[M], b = new int[M];
        for (int i = 0; i < M; i++) { a[i] = r.nextInt(N); b[i] = r.nextInt(N); }
        for (int round = 1; round <= 2; round++) {
            UnionFind uf = new UnionFind(N, true, true);
            long t0 = System.nanoTime(); int yes = 0;
            for (int i = 0; i < M; i++) { uf.union(a[i], b[i]); if (uf.same(r.nextInt(N), 1024)) yes++; }
            System.out.printf("第 %d 轮：并查集 %d ms，最后剩 %,d 个团伙（其中和 1024 同伙的查询 %,d 次）%n", round, (System.nanoTime() - t0) / 1_000_000, uf.groups, yes);
        }
        // 对比：每来 1 万条边做一次 BFS 回答同一个问题，只做前 20 万条边
        List<List<Integer>> adj = new ArrayList<>(N);
        for (int i = 0; i < N; i++) adj.add(new ArrayList<Integer>(2));
        long t0 = System.nanoTime(); int bfsRuns = 0;
        for (int i = 0; i < 200_000; i++) {
            adj.get(a[i]).add(b[i]); adj.get(b[i]).add(a[i]);
            if (i % 10_000 == 9_999) { bfs(adj, 1024, N); bfsRuns++; }
        }
        System.out.printf("对比：只做前 20 万条边、每 1 万条才 BFS 一次（共 %d 次）：%d ms%n", bfsRuns, (System.nanoTime() - t0) / 1_000_000);

        System.out.println("\n== 坑 1：合并时不找根，直接改 parent[a] = b");
        int[] p = {0, 1, 2, 3};
        p[1] = 0;            // union(1, 0)：对
        p[1] = 2;            // union(1, 2)：错，应该把 1 的根 0 挂过去，结果 1 被拽走了，0 被丢下
        int r0 = 0; while (p[r0] != r0) r0 = p[r0];
        int r2 = 2; while (p[r2] != r2) r2 = p[r2];
        System.out.println("先并 0—1、再并 1—2，0 和 2 一伙吗：" + (r0 == r2) + "（应为 true）");
    }

    static int bfs(List<List<Integer>> adj, int src, int n) {
        boolean[] seen = new boolean[n]; ArrayDeque<Integer> q = new ArrayDeque<>(); q.offer(src); seen[src] = true; int c = 0;
        while (!q.isEmpty()) { int u = q.poll(); c++; for (int v : adj.get(u)) if (!seen[v]) { seen[v] = true; q.offer(v); } }
        return c;
    }
}
