package labs.algo.backtracking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 第 20 课 · 回溯。全景第 12 站“回测”。
 * 对手方挂了几张单，数量各不相同；要正好吃够 target 手，每张单要么整张吃、要么不吃，列出所有组合。
 * 1) 回溯模板：做选择 → 递归 → 撤销
 * 2) 三种剪枝：超了就停（要先排序）、剩下全加也不够就停、同层跳过相同数量（去重）
 * 3) 两个 Java 坑：res.add(path) 存了引用；忘了撤销
 */
public class FillOrders {

    static long nodes;

    /** 不剪枝：每个子集都走到。 */
    static void plain(int[] a, int start, int sum, int target, List<Integer> path, List<List<Integer>> res) {
        nodes++;
        if (sum == target) res.add(new ArrayList<Integer>(path));
        for (int i = start; i < a.length; i++) {
            path.add(a[i]);
            plain(a, i + 1, sum + a[i], target, path, res);
            path.remove(path.size() - 1);
        }
    }

    /** 剪枝版：a 已经升序；suffix[i] = a[i..] 之和。 */
    static void pruned(int[] a, long[] suffix, int start, int sum, int target, List<Integer> path, List<List<Integer>> res) {
        nodes++;
        if (sum == target) { res.add(new ArrayList<Integer>(path)); return; }
        if (start < a.length && sum + suffix[start] < target) return;   // 剩下全加也不够
        for (int i = start; i < a.length; i++) {
            if (i > start && a[i] == a[i - 1]) continue;                 // 同一层不选相同数量，去重
            if (sum + a[i] > target) break;                               // 升序：这张超了，后面更超
            path.add(a[i]);
            pruned(a, suffix, i + 1, sum + a[i], target, path, res);
            path.remove(path.size() - 1);
        }
    }

    /** 坑 1：把 path 本身放进结果。 */
    static void bugRef(int[] a, int start, int sum, int target, List<Integer> path, List<List<Integer>> res) {
        if (sum == target) res.add(path);
        for (int i = start; i < a.length; i++) {
            path.add(a[i]);
            bugRef(a, i + 1, sum + a[i], target, path, res);
            path.remove(path.size() - 1);
        }
    }

    /** 坑 2：忘了撤销。 */
    static void bugNoUndo(int[] a, int start, int sum, int target, List<Integer> path, List<List<Integer>> res) {
        if (sum == target) res.add(new ArrayList<Integer>(path));
        for (int i = start; i < a.length; i++) {
            path.add(a[i]);
            bugNoUndo(a, i + 1, sum + a[i], target, path, res);
        }
    }

    static long[] suffix(int[] a) {
        long[] s = new long[a.length + 1];
        for (int i = a.length - 1; i >= 0; i--) s[i] = s[i + 1] + a[i];
        return s;
    }

    public static void main(String[] args) {
        int[] book = {2, 3, 5, 6};
        int target = 8;
        List<List<Integer>> res = new ArrayList<List<Integer>>();
        nodes = 0;
        plain(book, 0, 0, target, new ArrayList<Integer>(), res);
        System.out.println("挂单 " + Arrays.toString(book) + " 凑 " + target + "：" + res + "，不剪枝走了 " + nodes + " 个节点");
        res.clear(); nodes = 0;
        pruned(book, suffix(book), 0, 0, target, new ArrayList<Integer>(), res);
        System.out.println("                 剪枝后 " + res + "，走了 " + nodes + " 个节点");

        int[] dup = {2, 3, 3, 5};
        res.clear(); nodes = 0;
        plain(dup, 0, 0, 5, new ArrayList<Integer>(), res);
        System.out.println("挂单 " + Arrays.toString(dup) + " 凑 5，不去重：" + res);
        res.clear();
        pruned(dup, suffix(dup), 0, 0, 5, new ArrayList<Integer>(), res);
        System.out.println("                同层跳过相同：" + res);

        res.clear();
        bugRef(book, 0, 0, target, new ArrayList<Integer>(), res);
        System.out.println("坑 1 res.add(path)：" + res);
        res.clear();
        bugNoUndo(book, 0, 0, target, new ArrayList<Integer>(), res);
        System.out.println("坑 2 忘了撤销：" + res);

        // 规模：n 张随机挂单（1~40 手），凑 60 手
        Random r = new Random(20);
        for (int n : new int[]{16, 20, 24, 28}) {
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = 1 + r.nextInt(40);
            Arrays.sort(a);
            String line = "n=" + n + "：";
            if (n <= 24) {
                res.clear(); nodes = 0;
                long t0 = System.nanoTime();
                plain(a, 0, 0, 60, new ArrayList<Integer>(), res);
                line += "不剪枝 " + nodes + " 节点 " + (System.nanoTime() - t0) / 1000000 + " ms，" + res.size() + " 种；";
            } else {
                line += "不剪枝 2^" + n + " = " + (1L << n) + " 节点（不跑）；";
            }
            res.clear(); nodes = 0;
            long t0 = System.nanoTime();
            pruned(a, suffix(a), 0, 0, 60, new ArrayList<Integer>(), res);
            line += "剪枝+去重 " + nodes + " 节点 " + (System.nanoTime() - t0) / 1000000 + " ms，" + res.size() + " 种";
            System.out.println(line);
        }
    }
}
