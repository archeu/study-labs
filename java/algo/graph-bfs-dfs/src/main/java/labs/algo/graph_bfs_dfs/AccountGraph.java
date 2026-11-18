package labs.algo.graph_bfs_dfs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账户关联图（无向）：同一设备、同一 IP、互相转过账，就连一条边。
 * 邻接表：账户 → 和它直接相连的账户。BFS 求“隔几跳”和关联路径，DFS 求整片连通的账户。
 */
public class AccountGraph {
    final Map<Long, List<Long>> adj = new HashMap<>();

    public void link(long a, long b) {
        adj.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
        adj.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
    }

    List<Long> nb(long a) { List<Long> l = adj.get(a); return l == null ? Collections.<Long>emptyList() : l; }

    /** BFS：从 src 出发，每个账户离 src 几跳；parent 用来还原路径。入队时就标记，免得重复入队。 */
    public Map<Long, Integer> bfs(long src, Map<Long, Long> parent, boolean trace) {
        Map<Long, Integer> dist = new LinkedHashMap<>();
        Deque<Long> q = new ArrayDeque<>();
        dist.put(src, 0); q.offer(src);
        while (!q.isEmpty()) {
            long u = q.poll();
            StringBuilder added = new StringBuilder();
            for (long v : nb(u)) {
                if (dist.containsKey(v)) continue;          // 见过了：图有环，不查这一句就会绕圈
                dist.put(v, dist.get(u) + 1);
                if (parent != null) parent.put(v, u);
                q.offer(v);
                added.append(v).append(' ');
            }
            if (trace) System.out.printf("出队 %d（第 %d 层）  新发现 %-16s 队列 %s%n", u, dist.get(u), added.length() == 0 ? "-" : added.toString().trim(), q);
        }
        return dist;
    }

    /** 从 parent 往回走，得到 src → dst 的一条最短关联路径。 */
    public static List<Long> path(Map<Long, Long> parent, long src, long dst) {
        List<Long> p = new ArrayList<>();
        for (Long x = dst; x != null; x = x == src ? null : parent.get(x)) p.add(x);
        Collections.reverse(p);
        return p;
    }

    /** DFS（显式栈）：src 所在的整片连通账户，按访问顺序。 */
    public List<Long> dfs(long src) {
        List<Long> order = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<Long> st = new ArrayDeque<>();
        st.push(src);
        while (!st.isEmpty()) {
            long u = st.pop();
            if (!seen.add(u)) continue;                     // 出栈时才标记：同一个点可能被压了几次，第一次弹出才算
            order.add(u);
            List<Long> l = nb(u);
            for (int i = l.size() - 1; i >= 0; i--) if (!seen.contains(l.get(i))) st.push(l.get(i));   // 倒着压，弹出时按原顺序
        }
        return order;
    }

    /** DFS（递归）：同样的顺序，深度 = 路径长度。 */
    public void dfsRec(long u, Set<Long> seen, List<Long> order) {
        if (!seen.add(u)) return;
        order.add(u);
        for (long v : nb(u)) dfsRec(v, seen, order);
    }

    public static AccountGraph sample() {
        AccountGraph g = new AccountGraph();
        long[][] edges = {{1024, 2001}, {1024, 2002}, {2001, 3001}, {2002, 3001}, {2002, 3002},
                          {3001, 4001}, {3002, 7777}, {4001, 7777}, {5005, 5006}};
        for (long[] e : edges) g.link(e[0], e[1]);
        return g;
    }

    public static void main(String[] args) {
        AccountGraph g = sample();
        System.out.println("邻接表：" + g.adj);
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> d = g.bfs(1024, parent, true);
        System.out.println("离 1024 的跳数：" + d);
        System.out.println("1024 → 7777 的一条最短关联路径：" + path(parent, 1024, 7777));
        System.out.println("DFS（显式栈）访问顺序：" + g.dfs(1024));
        List<Long> rec = new ArrayList<>(); g.dfsRec(1024, new HashSet<Long>(), rec);
        System.out.println("DFS（递归）访问顺序：  " + rec);
        System.out.println("5005 在不在 1024 这一片里：" + d.containsKey(5005L));
    }
}
