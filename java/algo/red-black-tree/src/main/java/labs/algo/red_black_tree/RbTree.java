package labs.algo.red_black_tree;

/**
 * 只有插入的红黑树（和 TreeMap.fixAfterInsertion 同一套情况划分），每一步修复都打印出来。
 * 用来看“一路上涨的价位”插进来时，旋转和变色怎么把树高压住。
 */
public class RbTree {
    static final boolean RED = true, BLACK = false;

    static final class Node {
        long key; boolean color = RED; Node left, right, parent;
        Node(long key, Node parent) { this.key = key; this.parent = parent; }
    }

    Node root;
    boolean trace;

    public void insert(long key) {
        Node p = null, cur = root;
        while (cur != null) { p = cur; if (key == cur.key) return; cur = key < cur.key ? cur.left : cur.right; }
        Node x = new Node(key, p);
        if (p == null) root = x; else if (key < p.key) p.left = x; else p.right = x;
        if (trace) System.out.printf("插入 %d（红）            %s%n", key, show());
        fix(x);
    }

    private void fix(Node x) {
        while (x != root && color(x.parent) == RED) {
            Node p = x.parent, g = p.parent;
            boolean pLeft = p == g.left;
            Node uncle = pLeft ? g.right : g.left;
            if (color(uncle) == RED) {                       // 情况 1：叔叔红 → 父、叔变黑，祖父变红，问题上移两层
                p.color = BLACK; uncle.color = BLACK; g.color = RED;
                if (trace) System.out.printf("  叔叔 %d 是红的：变色     %s%n", uncle.key, show());
                x = g;
            } else {
                if (x == (pLeft ? p.right : p.left)) {         // 情况 2：内侧 → 先在父亲处转一下，变成外侧
                    x = p;
                    if (pLeft) rotateLeft(x); else rotateRight(x);
                    if (trace) System.out.printf("  内侧：在 %d 处旋转成外侧  %s%n", x.key, show());
                    p = x.parent;
                }
                p.color = BLACK; g.color = RED;               // 情况 3：外侧 → 父变黑、祖父变红，在祖父处反向旋转
                if (pLeft) rotateRight(g); else rotateLeft(g);
                if (trace) System.out.printf("  叔叔是黑的（或空）：在 %d 处旋转 + 变色  %s%n", g.key, show());
            }
        }
        root.color = BLACK;
    }

    /** 左旋：x 的右孩子 y 上来，x 下去当 y 的左孩子，y 原来的左子树挂到 x 的右边。中序顺序不变。 */
    private void rotateLeft(Node x) {
        Node y = x.right;
        x.right = y.left; if (y.left != null) y.left.parent = x;
        y.parent = x.parent;
        if (x.parent == null) root = y; else if (x == x.parent.left) x.parent.left = y; else x.parent.right = y;
        y.left = x; x.parent = y;
    }

    private void rotateRight(Node x) {
        Node y = x.left;
        x.left = y.right; if (y.right != null) y.right.parent = x;
        y.parent = x.parent;
        if (x.parent == null) root = y; else if (x == x.parent.right) x.parent.right = y; else x.parent.left = y;
        y.right = x; x.parent = y;
    }

    static boolean color(Node n) { return n == null ? BLACK : n.color; }

    static int height(Node n) { return n == null ? 0 : 1 + Math.max(height(n.left), height(n.right)); }

    /** 前序打印成 根(左,右)，红节点后面加 *。 */
    String show() { StringBuilder sb = new StringBuilder(); show(root, sb); return sb.toString(); }
    private static void show(Node n, StringBuilder sb) {
        if (n == null) { sb.append('·'); return; }
        sb.append(n.key % 1000).append(n.color == RED ? "*" : "");
        if (n.left == null && n.right == null) return;
        sb.append('('); show(n.left, sb); sb.append(','); show(n.right, sb); sb.append(')');
    }

    public static void main(String[] args) {
        RbTree t = new RbTree();
        t.trace = true;
        System.out.println("一路上涨的卖盘价位（只打印后三位，* = 红）：");
        for (long p : new long[]{10020, 10050, 10080, 10100, 10150, 10200, 10300}) t.insert(p);
        System.out.println("树高 " + height(t.root) + "（第 13 课不平衡的树，同样的插入顺序，树高 7）");
    }
}
