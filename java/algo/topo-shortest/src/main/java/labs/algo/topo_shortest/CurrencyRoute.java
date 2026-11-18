package labs.algo.topo_shortest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 换汇路线：每条边是一次兑换的成本（手续费 + 滑点，单位 bp = 万分之一），找 BTC → ETH 总成本最低的路。
 * Dijkstra = BFS 的队列换成“按当前距离排序”的堆（第 6 课）。PriorityQueue 不能改优先级，所以用“延迟删除”：
 * 距离变短就再放一份进去，弹出来发现是旧的就跳过。
 */
public class CurrencyRoute {
    final String[] name;
    final List<int[]>[] adj;                                  // {到哪, 成本}

    @SuppressWarnings("unchecked")
    CurrencyRoute(String... names) { name = names; adj = new List[names.length]; for (int i = 0; i < names.length; i++) adj[i] = new ArrayList<>(); }

    int id(String s) { for (int i = 0; i < name.length; i++) if (name[i].equals(s)) return i; throw new IllegalArgumentException(s); }

    void both(String a, String b, int bp) { adj[id(a)].add(new int[]{id(b), bp}); adj[id(b)].add(new int[]{id(a), bp}); }
    void one(String a, String b, int bp) { adj[id(a)].add(new int[]{id(b), bp}); }

    /** 返回 dist；prev 用来还原路径。finalizeOnPop=false 时演示“入队就定下来”的错误写法。 */
    long[] dijkstra(int src, int[] prev, boolean finalizeOnPop, boolean trace, long[] stalePops) {
        int n = name.length;
        long[] dist = new long[n]; Arrays.fill(dist, Long.MAX_VALUE); Arrays.fill(prev, -1);
        boolean[] done = new boolean[n];
        PriorityQueue<long[]> pq = new PriorityQueue<>((x, y) -> Long.compare(x[0], y[0]));
        dist[src] = 0; pq.offer(new long[]{0, src});
        if (!finalizeOnPop) done[src] = true;
        while (!pq.isEmpty()) {
            long[] top = pq.poll(); int u = (int) top[1];
            if (top[0] > dist[u] || (finalizeOnPop && done[u])) { if (stalePops != null) stalePops[0]++; continue; }   // 旧的那份，跳过
            if (finalizeOnPop) done[u] = true;                 // 弹出来的时候才确定：非负边保证它不会再变短
            StringBuilder upd = new StringBuilder();
            for (int[] e : adj[u]) {
                int v = e[0];
                if (done[v]) continue;
                long nd = dist[u] + e[1];
                if (nd < dist[v]) {
                    upd.append(name[v]).append(' ').append(dist[v] == Long.MAX_VALUE ? "∞" : String.valueOf(dist[v])).append("→").append(nd).append("  ");
                    dist[v] = nd; prev[v] = u; pq.offer(new long[]{nd, v});
                    if (!finalizeOnPop) done[v] = true;          // 错误写法：第一次碰到就定下来（像 BFS 那样）
                }
            }
            if (trace) System.out.printf("确定 %-4s = %-3d  松弛：%s%n", name[u], dist[u], upd.length() == 0 ? "-" : upd);
        }
        return dist;
    }

    /** Bellman-Ford：所有边松弛 n−1 轮，能处理负边；第 n 轮还能变短就是有负环。 */
    long[] bellmanFord(int src, boolean[] negCycle) {
        int n = name.length; long[] dist = new long[n]; Arrays.fill(dist, Long.MAX_VALUE / 4); dist[src] = 0;
        for (int round = 0; round < n; round++) {
            boolean changed = false;
            for (int u = 0; u < n; u++) for (int[] e : adj[u]) if (dist[u] + e[1] < dist[e[0]]) { dist[e[0]] = dist[u] + e[1]; changed = true; }
            if (round == n - 1 && changed) negCycle[0] = true;
        }
        return dist;
    }

    List<String> path(int[] prev, int dst) { List<String> p = new ArrayList<>(); for (int x = dst; x != -1; x = prev[x]) p.add(name[x]); Collections.reverse(p); return p; }

    public static CurrencyRoute sample() {
        CurrencyRoute g = new CurrencyRoute("BTC", "USDT", "ETH", "USDC", "BNB");
        g.both("BTC", "USDT", 10); g.both("BTC", "ETH", 30); g.both("BTC", "USDC", 8); g.both("BTC", "BNB", 12);
        g.both("USDT", "ETH", 10); g.both("USDC", "ETH", 15); g.both("BNB", "ETH", 7);
        g.both("USDT", "USDC", 1); g.both("USDT", "BNB", 6);
        return g;
    }

    public static void main(String[] args) {
        CurrencyRoute g = sample();
        int[] prev = new int[5];
        long[] stale = new long[1];
        long[] d = g.dijkstra(0, prev, true, true, stale);
        System.out.println("BTC → ETH 最低成本 " + d[2] + " bp，路线 " + g.path(prev, 2) + "；堆里弹出的旧条目 " + stale[0] + " 个");
        double amount = 1.0; for (int bp : new int[]{8, 1, 10}) amount *= 1 - bp / 10000.0;
        System.out.printf("1 BTC 走这条路，按成本算剩 %.6f BTC 等值（直接换要扣 30 bp，剩 %.6f）%n%n", amount, 1 - 30 / 10000.0);

        long[] wrong = g.dijkstra(0, new int[5], false, false, null);
        System.out.println("错误写法（入队就定下来）：BTC → ETH = " + wrong[2] + " bp");

        CurrencyRoute neg = new CurrencyRoute("A", "B", "C", "D");
        neg.one("A", "B", 5); neg.one("A", "C", 2); neg.one("B", "C", -4); neg.one("C", "D", 1);   // B→C 有返佣，成本为负
        long[] dj = neg.dijkstra(0, new int[4], true, false, null);
        long[] bf = neg.bellmanFord(0, new boolean[1]);
        System.out.println("有负边时 A → D：Dijkstra " + dj[3] + "，Bellman-Ford " + bf[3]);
    }

    /** 随机图上：堆版 Dijkstra vs 每次线性扫一遍找最小（O(V²)）。 */
    static void bench() {
        int n = 20_000, m = 200_000; Random r = new Random(1);
        String[] names = new String[n]; for (int i = 0; i < n; i++) names[i] = "v" + i;
        CurrencyRoute g = new CurrencyRoute(names);
        for (int i = 0; i < m; i++) g.adj[r.nextInt(n)].add(new int[]{r.nextInt(n), 1 + r.nextInt(100)});
        for (int round = 1; round <= 2; round++) {
            long t0 = System.nanoTime(); long[] a = g.dijkstra(0, new int[n], true, false, null); long th = System.nanoTime() - t0;
            t0 = System.nanoTime();
            long[] dist = new long[n]; Arrays.fill(dist, Long.MAX_VALUE); boolean[] done = new boolean[n]; dist[0] = 0;
            for (int it = 0; it < n; it++) {
                int u = -1; for (int v = 0; v < n; v++) if (!done[v] && (u < 0 || dist[v] < dist[u])) u = v;
                if (dist[u] == Long.MAX_VALUE) break;
                done[u] = true;
                for (int[] e : g.adj[u]) if (dist[u] + e[1] < dist[e[0]]) dist[e[0]] = dist[u] + e[1];
            }
            long tn = System.nanoTime() - t0;
            System.out.printf("第 %d 轮：%,d 个点 %,d 条边，堆版 %d ms，O(V²) 版 %d ms，结果一致 %s%n", round, n, m, th / 1_000_000, tn / 1_000_000, Arrays.equals(a, dist));
        }
    }
}
