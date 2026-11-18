package labs.algo.big_o_counting;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * 双倍法：n 翻倍，看耗时变成几倍。
 * 比值 ≈ 2 → O(n)；≈ 2.1 → O(n log n)；≈ 4 → O(n²)。
 * 粗测，不是 JMH：热身 3 次，取 5 次里最快的一次。只看倍数，不看绝对值。
 */
public class Doubling {

    interface Algo { boolean run(int[] a, int target); }

    // O(n²)：两两都试
    static boolean brute(int[] a, int target) {
        for (int i = 0; i < a.length; i++)
            for (int j = i + 1; j < a.length; j++)
                if (a[i] + a[j] == target) return true;
        return false;
    }

    // O(n log n)：先排序，再两头往中间夹
    static boolean sortTwoPointers(int[] a, int target) {
        int[] b = a.clone();
        Arrays.sort(b);
        int i = 0, j = b.length - 1;
        while (i < j) {
            int s = b[i] + b[j];
            if (s == target) return true;
            if (s < target) i++; else j--;
        }
        return false;
    }

    // O(n)（平均）：边走边查“另一半见过没有”
    static boolean hashSet(int[] a, int target) {
        Set<Integer> seen = new HashSet<>(a.length * 2);
        for (int v : a) {
            if (seen.contains(target - v)) return true;
            seen.add(v);
        }
        return false;
    }

    static volatile boolean sink;

    static double bestMs(Algo f, int[] a) {
        for (int w = 0; w < 3; w++) sink = f.run(a, -1);
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t = System.nanoTime();
            sink = f.run(a, -1);                        // 全是正数，-1 找不到：跑满，就是最坏情况
            best = Math.min(best, (System.nanoTime() - t) / 1e6);
        }
        return best;
    }

    static void series(String name, Algo f, int from, int times) {
        System.out.println("== " + name + " ==");
        Random rnd = new Random(42);
        double prev = 0;
        for (int k = 0, n = from; k < times; k++, n *= 2) {
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = 1 + rnd.nextInt(1_000_000_000);
            double ms = bestMs(f, a);
            System.out.printf("  n = %,10d   %9.2f ms   %s%n", n, ms,
                    prev == 0 ? "" : String.format("比上一行 ×%.2f", ms / prev));
            prev = ms;
        }
    }

    public static void main(String[] args) {
        series("双重循环 O(n²)", Doubling::brute, 4_000, 4);
        series("排序 + 双指针 O(n log n)", Doubling::sortTwoPointers, 1_000_000, 4);
        series("HashSet O(n)", Doubling::hashSet, 1_000_000, 4);
    }
}
