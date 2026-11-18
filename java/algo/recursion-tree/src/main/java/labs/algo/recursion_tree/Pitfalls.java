package labs.algo.recursion_tree;

import java.util.ArrayList;
import java.util.List;

/** 页面 ⑥ 的坑。 */
public class Pitfalls {
    static long calls;

    public static void main(String[] args) {
        degenerate();
        repeated();
    }

    /** 坑 2：价格一路涨，按到达顺序插入，树退化成链表；递归中序栈溢出，显式栈没事。 */
    static void degenerate() {
        System.out.println("== 坑 2：退化成链表的树");
        for (int n : new int[]{1_000, 5_000, 10_000, 20_000, 50_000, 100_000}) {
            PriceTree t = new PriceTree();
            // 一路涨：每个新价位都挂在最右边。和逐个 add 得到的树一样，直接接链省掉 O(n²) 的插入
            PriceTree.Node cur = null;
            for (int i = 0; i < n; i++) { PriceTree.Node nd = new PriceTree.Node(10_000 + i, 1); if (cur == null) t.root = nd; else cur.right = nd; cur = nd; }
            String rec;
            try { List<Long> out = new ArrayList<>(); PriceTree.inorder(t.root, out, 0, false); rec = "递归 OK"; }
            catch (StackOverflowError e) { rec = "递归 StackOverflowError"; }
            int it = PriceTree.inorderIter(t.root, 0).size();
            System.out.printf("n=%,7d 树高 %,7d   %s   显式栈 输出 %,d 个%n", n, n, rec, it);
        }
        System.out.println();
    }

    static long fib(int n) { calls++; return n < 2 ? n : fib(n - 1) + fib(n - 2); }

    /** 坑 3：递归里重复算同一个子问题。 */
    static void repeated() {
        System.out.println("== 坑 3：重复的子问题");
        for (int n : new int[]{20, 30, 40}) {
            calls = 0; long t0 = System.nanoTime(); long v = fib(n);
            System.out.printf("fib(%d) = %d：调用 %,d 次，%d ms%n", n, v, calls, (System.nanoTime() - t0) / 1_000_000);
        }
        long[] memo = new long[41]; memo[1] = 1;
        for (int i = 2; i <= 40; i++) memo[i] = memo[i - 1] + memo[i - 2];
        System.out.println("记下来再用（第 21 课的动态规划）：fib(40) = " + memo[40] + "，算 39 次");
    }
}
