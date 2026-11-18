package labs.algo.bplus_skiplist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 内存里的 B+ 树：key 是成交时间（long）。每个节点最多 order 个 key；
 * 内部节点只放路标，数据全在叶子，叶子之间用 next 串起来，范围查询找到起点后顺着叶子读。
 * 只做插入和范围查询，够看清“扇出越大，树越矮”。
 */
public class BPlusTree {
    final int order;
    Node root;
    public long nodesVisited;           // 一次查询读了几个节点（≈ 磁盘上读了几页）

    static class Node { int n; long[] keys; boolean leaf; Node[] kids; Node next; }

    public BPlusTree(int order) { this.order = order; root = newLeaf(); }

    Node newLeaf() { Node x = new Node(); x.leaf = true; x.keys = new long[order + 1]; return x; }
    Node newInner() { Node x = new Node(); x.keys = new long[order + 1]; x.kids = new Node[order + 2]; return x; }

    public void insert(long key) {
        Object[] split = insert(root, key);
        if (split != null) {                                 // 根分裂：树长高一层（只会从根长高）
            Node r = newInner();
            r.keys[0] = (Long) split[0]; r.kids[0] = root; r.kids[1] = (Node) split[1]; r.n = 1;
            root = r;
        }
    }

    /** 返回 null，或者 {上升的 key, 新的右兄弟}。 */
    private Object[] insert(Node x, long key) {
        int i = upper(x, key);
        if (x.leaf) {
            System.arraycopy(x.keys, i, x.keys, i + 1, x.n - i);
            x.keys[i] = key; x.n++;
            if (x.n <= order) return null;
            Node r = newLeaf();                              // 叶子满了：右半边搬到新叶子，复制第一个 key 上去
            int half = x.n / 2;
            r.n = x.n - half; System.arraycopy(x.keys, half, r.keys, 0, r.n); x.n = half;
            r.next = x.next; x.next = r;
            return new Object[]{r.keys[0], r};
        }
        Object[] up = insert(x.kids[i], key);
        if (up == null) return null;
        System.arraycopy(x.keys, i, x.keys, i + 1, x.n - i);
        System.arraycopy(x.kids, i + 1, x.kids, i + 2, x.n - i);
        x.keys[i] = (Long) up[0]; x.kids[i + 1] = (Node) up[1]; x.n++;
        if (x.n <= order) return null;
        Node r = newInner();                                 // 内部节点满了：中间的 key 上升（不复制）
        int mid = x.n / 2;
        long midKey = x.keys[mid];
        r.n = x.n - mid - 1;
        System.arraycopy(x.keys, mid + 1, r.keys, 0, r.n);
        System.arraycopy(x.kids, mid + 1, r.kids, 0, r.n + 1);
        x.n = mid;
        return new Object[]{midKey, r};
    }

    /** 节点里第一个 > key 的位置（等于的往右走）。 */
    static int upper(Node x, long key) { int lo = 0, hi = x.n; while (lo < hi) { int m = (lo + hi) >>> 1; if (x.keys[m] > key) hi = m; else lo = m + 1; } return lo; }
    static int lower(Node x, long key) { int lo = 0, hi = x.n; while (lo < hi) { int m = (lo + hi) >>> 1; if (x.keys[m] >= key) hi = m; else lo = m + 1; } return lo; }

    /** [lo, hi] 范围内的 key 个数；nodesVisited 记下读了几个节点。 */
    public int rangeCount(long lo, long hi) {
        nodesVisited = 0;
        Node x = root;
        while (!x.leaf) { nodesVisited++; x = x.kids[upper(x, lo - 1)]; }   // 往下找第一个可能 >= lo 的叶子
        int cnt = 0;
        for (int i = lower(x, lo); x != null; x = x.next, i = 0) {
            nodesVisited++;
            for (; i < x.n; i++) { if (x.keys[i] > hi) return cnt; cnt++; }
        }
        return cnt;
    }

    public int height() { int h = 1; for (Node x = root; !x.leaf; x = x.kids[0]) h++; return h; }

    String dump() {
        StringBuilder sb = new StringBuilder();
        List<Node> lv = new ArrayList<>(); lv.add(root);
        while (!lv.isEmpty()) {
            List<Node> nx = new ArrayList<>();
            for (Node x : lv) { sb.append(Arrays.toString(Arrays.copyOf(x.keys, x.n))).append(' '); if (!x.leaf) for (int i = 0; i <= x.n; i++) nx.add(x.kids[i]); }
            sb.append('\n'); lv = nx;
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        BPlusTree t = new BPlusTree(3);                      // 每个节点最多 3 个 key，方便看分裂
        for (long k : new long[]{10, 20, 30, 40, 50, 60, 70, 80, 90}) t.insert(k);
        System.out.print("order=3，插入 10..90：\n" + t.dump());
        System.out.println("范围 [35, 75] 有 " + t.rangeCount(35, 75) + " 个，读了 " + t.nodesVisited + " 个节点");
    }
}
