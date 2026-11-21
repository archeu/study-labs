package labs.algo.problem_map;

import java.util.Arrays;
import java.util.Random;

/**
 * 看规模定算法：在这台机器上，1 秒大约能做多少次“简单操作”？
 * 分别量 O(n)、O(n log n)、O(n²) 三种循环，算出每秒多少次，用来校准“n 是多少时只能用什么复杂度”。
 */
public class Budget {
    public static void main(String[] args) {
        Random r = new Random(25);
        for (int round = 1; round <= 3; round++) {
            // O(n)：1 亿次加法和取模
            int n1 = 100_000_000;
            long t0 = System.nanoTime(), s = 0;
            for (int i = 0; i < n1; i++) s += i % 7;
            double linear = (System.nanoTime() - t0) / 1e6;

            // O(n log n)：100 万个 int 排序
            int[] a = new int[1_000_000];
            for (int i = 0; i < a.length; i++) a[i] = r.nextInt();
            t0 = System.nanoTime();
            Arrays.sort(a);
            double sort = (System.nanoTime() - t0) / 1e6;

            // O(n²)：n = 1 万的双重循环，共 1 亿次内层
            int n2 = 10_000;
            int[] b = new int[n2];
            for (int i = 0; i < n2; i++) b[i] = r.nextInt(1000);
            t0 = System.nanoTime();
            long pairs = 0;
            for (int i = 0; i < n2; i++) for (int j = i + 1; j < n2; j++) if (b[i] + b[j] == 1000) pairs++;
            double quad = (System.nanoTime() - t0) / 1e6;

            System.out.printf("第 %d 轮：1 亿次简单循环 %.0f ms；100 万个 int 排序 %.0f ms；n=1 万的两两配对（5000 万对）%.0f ms（%d %d）%n",
                    round, linear, sort, quad, s % 10, pairs % 10);
        }
    }
}
