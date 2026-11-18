package labs.algo.bplus_skiplist;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 成交额排行榜：分数（成交额，分）→ 用户。仿 Redis ZSet 的跳表：
 * 每个节点随机层数（p = 1/4，最多 32 层），每根前进指针带 span（跨过几个节点），所以能 O(log n) 按排名查。
 */
public class SkipList {
    static final int MAX = 32;
    static final double P = 0.25;

    static final class Node {
        final long score; final String member;
        final Node[] next; final int[] span;
        Node(int level, long score, String member) { this.score = score; this.member = member; next = new Node[level]; span = new int[level]; }
    }

    final Node head = new Node(MAX, Long.MIN_VALUE, null);
    int level = 1, size;
    final Random rnd;
    public long compares;               // 累计比较次数，测速用
    public boolean trace;

    public SkipList(long seed) { rnd = new Random(seed); }

    int randomLevel() { int l = 1; while (l < MAX && rnd.nextDouble() < P) l++; return l; }

    public void insert(long score, String member) { insert(score, member, randomLevel()); }

    /** 插入；可以指定层数，方便和页面的动画对上。 */
    public void insert(long score, String member, int lvl) {
        Node[] update = new Node[MAX];
        int[] rank = new int[MAX];                       // rank[i] = 在第 i 层走到 update[i] 时，已经跨过了几个节点
        Node x = head;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = i == level - 1 ? 0 : rank[i + 1];
            while (x.next[i] != null && less(x.next[i], score, member)) { rank[i] += x.span[i]; x = x.next[i]; }
            update[i] = x;
        }
        if (lvl > level) {
            for (int i = level; i < lvl; i++) { rank[i] = 0; update[i] = head; head.span[i] = size; }
            level = lvl;
        }
        Node n = new Node(lvl, score, member);
        for (int i = 0; i < lvl; i++) {
            n.next[i] = update[i].next[i];
            update[i].next[i] = n;
            n.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = rank[0] - rank[i] + 1;
        }
        for (int i = lvl; i < level; i++) update[i].span[i]++;
        size++;
        if (trace) System.out.printf("插入 %s=%d（%d 层）  %s%n", member, score, lvl, dump());
    }

    boolean less(Node a, long score, String member) {
        compares++;
        return a.score < score || (a.score == score && a.member.compareTo(member) < 0);
    }

    /** 分数第一个 >= score 的节点；trace 时打印走过的路径。 */
    public Node ceiling(long score) {
        Node x = head;
        StringBuilder path = trace ? new StringBuilder() : null;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null) {
                compares++;
                if (x.next[i].score < score) { x = x.next[i]; if (trace) path.append("第").append(i + 1).append("层→").append(x.member).append(' '); }
                else break;
            }
            if (trace && i > 0) path.append("下到第").append(i).append("层 ");
        }
        if (trace) System.out.println("找第一个 >= " + score + "：" + path + "→ " + (x.next[0] == null ? "无" : x.next[0].member));
        return x.next[0];
    }

    /** 分数从小到大的排名（1 起）；沿路加 span。 */
    public int rank(long score, String member) {
        Node x = head; int r = 0;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && (x.next[i].score < score || (x.next[i].score == score && x.next[i].member.compareTo(member) <= 0))) {
                r += x.span[i]; x = x.next[i];
            }
            if (x.member != null && x.member.equals(member)) return r;
        }
        return 0;
    }

    /** [lo, hi] 分数范围：先找起点，再沿第 0 层往后走。 */
    public List<String> range(long lo, long hi) {
        List<String> out = new ArrayList<>();
        for (Node x = ceiling(lo); x != null && x.score <= hi; x = x.next[0]) out.add(x.member + "=" + x.score);
        return out;
    }

    String dump() {
        StringBuilder sb = new StringBuilder();
        for (int i = level - 1; i >= 0; i--) {
            sb.append("L").append(i + 1).append(':');
            for (Node x = head.next[i]; x != null; x = x.next[i]) sb.append(' ').append(x.member);
            if (i > 0) sb.append(" | ");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        SkipList z = new SkipList(1);
        z.trace = true;
        // 成交额（万元）和层数固定下来，和页面 ④ 的动画一样
        String[] users = {"u11", "u23", "u35", "u47", "u52", "u68", "u74", "u89"};
        long[] scores = {12, 23, 35, 47, 52, 68, 74, 89};
        int[] levels = {1, 2, 1, 3, 1, 2, 1, 1};
        for (int i = 0; i < users.length; i++) z.insert(scores[i], users[i], levels[i]);
        z.ceiling(70);
        System.out.println("u74 从小到大排第 " + z.rank(74, "u74") + "，从大到小排第 " + (z.size - z.rank(74, "u74") + 1));
        z.trace = false;
        System.out.println("成交额 [40, 75] 的用户：" + z.range(40, 75));
    }
}
