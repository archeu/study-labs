package labs.algo.recursion_tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 卖盘的价位放进一棵二叉搜索树（不做平衡，平衡是第 14 课的事）。
 * 卖盘最优 = 价格最低，所以“从优到劣”= 从小到大 = 中序遍历。
 */
public class PriceTree {
    static final class Node {
        final long price; long qty; Node left, right;
        Node(long price, long qty) { this.price = price; this.qty = qty; }
    }

    Node root;
    int size;

    /** 插入：比当前小往左，比当前大往右，相等就累加数量（同一价位）。迭代写，免得退化时栈溢出。 */
    public void add(long price, long qty) {
        if (root == null) { root = new Node(price, qty); size++; return; }
        Node cur = root;
        while (true) {
            if (price == cur.price) { cur.qty += qty; return; }
            Node next = price < cur.price ? cur.left : cur.right;
            if (next == null) {
                if (price < cur.price) cur.left = new Node(price, qty); else cur.right = new Node(price, qty);
                size++; return;
            }
            cur = next;
        }
    }

    /** 递归中序：左子树全部 → 自己 → 右子树全部。depth 只为打印缩进。 */
    static void inorder(Node n, List<Long> out, int depth, boolean trace) {
        if (n == null) return;                                  // 基础情况：空树什么都不做
        if (trace) System.out.printf("%s进入 %d%n", indent(depth), n.price);
        inorder(n.left, out, depth + 1, trace);                // 相信它：回来时左子树已经按顺序输出了
        out.add(n.price);
        if (trace) System.out.printf("%s输出 %d   已输出 %s%n", indent(depth), n.price, out);
        inorder(n.right, out, depth + 1, trace);
    }

    /** 显式栈中序：一路向左压栈，弹出一个输出，再转向它的右子树。k > 0 时输出 k 个就停（前 k 档盘口）。 */
    static List<Long> inorderIter(Node root, int k) {
        List<Long> out = new ArrayList<>();
        Deque<Node> stack = new ArrayDeque<>();
        Node cur = root;
        while (cur != null || !stack.isEmpty()) {
            while (cur != null) { stack.push(cur); cur = cur.left; }   // 先把左边一路压下去
            Node n = stack.pop();
            out.add(n.price);
            if (k > 0 && out.size() == k) break;                      // 前 k 档够了
            cur = n.right;
        }
        return out;
    }

    static void preorder(Node n, List<Long> out) { if (n == null) return; out.add(n.price); preorder(n.left, out); preorder(n.right, out); }
    static void postorder(Node n, List<Long> out) { if (n == null) return; postorder(n.left, out); postorder(n.right, out); out.add(n.price); }

    /** 后序的典型用法：先知道两个孩子的答案，再算自己。树高 = 1 + max(左高, 右高)。 */
    static int height(Node n) { return n == null ? 0 : 1 + Math.max(height(n.left), height(n.right)); }

    /** 层序：队列，一层一层。 */
    static List<List<Long>> levels(Node root) {
        List<List<Long>> res = new ArrayList<>();
        Deque<Node> q = new ArrayDeque<>();
        if (root != null) q.offer(root);
        while (!q.isEmpty()) {
            List<Long> lv = new ArrayList<>();
            for (int i = q.size(); i > 0; i--) { Node n = q.poll(); lv.add(n.price); if (n.left != null) q.offer(n.left); if (n.right != null) q.offer(n.right); }
            res.add(lv);
        }
        return res;
    }

    static String indent(int d) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < d; i++) sb.append("  "); return sb.toString(); }

    public static void main(String[] args) {
        PriceTree t = new PriceTree();
        // 卖盘价位（单位：分），按挂单到达的顺序插入
        long[] prices = {10100, 10050, 10200, 10020, 10080, 10150, 10300};
        for (long p : prices) t.add(p, 1);
        System.out.println("层序（树的形状）：" + levels(t.root));
        List<Long> out = new ArrayList<>();
        inorder(t.root, out, 0, true);
        List<Long> pre = new ArrayList<>(), post = new ArrayList<>();
        preorder(t.root, pre); postorder(t.root, post);
        System.out.println("前序 " + pre + "\n中序 " + out + "  ← 从优到劣\n后序 " + post);
        System.out.println("显式栈，前 5 档：" + inorderIter(t.root, 5));
        System.out.println("树高 " + height(t.root));
    }
}
