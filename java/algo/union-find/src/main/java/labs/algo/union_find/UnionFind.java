package labs.algo.union_find;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 并查集：账户一对对“合并成团伙”，随时问“a 和 b 是不是一伙”。
 * parent[i] 指向同伙里的另一个账户，沿着走到底（parent[r] == r）就是这伙人的代表。
 * 两个优化可以分开开关，方便对比：按大小合并（小的挂到大的下面）、路径压缩（查一次，路上的都直接挂到根）。
 */
public class UnionFind {
    final int[] parent, size;
    final boolean bySize, compress;
    int groups;
    public long steps;                       // find 时沿 parent 走的总步数

    public UnionFind(int n, boolean bySize, boolean compress) {
        parent = new int[n]; size = new int[n];
        for (int i = 0; i < n; i++) { parent[i] = i; size[i] = 1; }
        this.bySize = bySize; this.compress = compress; groups = n;
    }

    /** 找代表：迭代写，免得长链上栈溢出。 */
    public int find(int x) {
        int r = x;
        while (parent[r] != r) { r = parent[r]; steps++; }
        if (compress) while (parent[x] != r) { int next = parent[x]; parent[x] = r; x = next; }   // 路上的都直接指向根
        return r;
    }

    /** 合并 a、b 所在的两伙；已经是一伙返回 false。 */
    public boolean union(int a, int b) {
        int ra = find(a), rb = find(b);
        if (ra == rb) return false;
        if (bySize && size[ra] < size[rb]) { int t = ra; ra = rb; rb = t; }   // 小的挂到大的下面
        parent[rb] = ra; size[ra] += size[rb]; groups--;
        return true;
    }

    public boolean same(int a, int b) { return find(a) == find(b); }

    int depth(int x) { int d = 0; while (parent[x] != x) { x = parent[x]; d++; } return d; }

    /** 账户号是稀疏的 long，先映射成 0..n-1（第 8 课的哈希表）。 */
    static final class Ids {
        final Map<Long, Integer> idx = new HashMap<>(); final long[] back;
        Ids(long... accounts) { back = accounts; for (int i = 0; i < accounts.length; i++) idx.put(accounts[i], i); }
        int of(long acc) { return idx.get(acc); }
    }

    public static void main(String[] args) {
        Ids ids = new Ids(1024, 2001, 2002, 3001, 3002, 4001, 7777, 5005);
        UnionFind uf = new UnionFind(8, true, true);
        long[][] links = {{1024, 2001}, {3001, 4001}, {2002, 3002}, {3002, 7777}, {2001, 3001}, {2002, 4001}};
        for (long[] e : links) {
            boolean merged = uf.union(ids.of(e[0]), ids.of(e[1]));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(ids.back[i]).append("→").append(ids.back[uf.parent[i]]).append(i < 7 ? "  " : "");
            System.out.printf("关联 %d—%d：%s   %s   团伙数 %d%n", e[0], e[1], merged ? "合并" : "已经是一伙", sb, uf.groups);
        }
        System.out.println("1024 和 7777 一伙吗：" + uf.same(ids.of(1024), ids.of(7777)) + "；1024 和 5005：" + uf.same(ids.of(1024), ids.of(5005)));
        System.out.println("parent 数组（下标对应 " + Arrays.toString(ids.back) + "）：" + Arrays.toString(uf.parent));
    }
}
