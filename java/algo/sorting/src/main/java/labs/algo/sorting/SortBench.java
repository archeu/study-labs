package labs.algo.sorting;

import java.util.Arrays;
import java.util.Random;

/**
 * 500 万个成交号排序：随机 vs 几乎有序（多线程写流水，偶尔前后颠倒）。
 * 手写快排、JDK 双轴快排（long[]）、TimSort（Long[]）、parallelSort。
 * java -Xms2g -Xmx2g -cp algo/sorting/target/classes labs.algo.sorting.SortBench
 */
public class SortBench {
    public static void main(String[] args) {
        int n = 5_000_000;
        Random r = new Random(11);
        long[] random = new long[n], nearly = new long[n];
        for (int i = 0; i < n; i++) { random[i] = r.nextLong() & Long.MAX_VALUE; nearly[i] = 1_000_000_000L + i; }
        for (int k = 0; k < n / 100; k++) {             // 1% 的位置和附近 10 个以内的交换
            int i = r.nextInt(n - 10), j = i + 1 + r.nextInt(9);
            long t = nearly[i]; nearly[i] = nearly[j]; nearly[j] = t;
        }
        for (int round = 1; round <= 3; round++) {
            System.out.println("-- 第 " + round + " 轮（毫秒）");
            for (String name : new String[]{"随机", "几乎有序"}) {
                long[] src = name.equals("随机") ? random : nearly;
                long[] a = src.clone(); long t0 = System.nanoTime();
                Sorts.quickSort(a, 0, n - 1, true);
                long q = ms(t0);
                a = src.clone(); t0 = System.nanoTime(); Arrays.sort(a); long d = ms(t0);
                a = src.clone(); t0 = System.nanoTime(); Arrays.parallelSort(a); long p = ms(t0);
                Long[] b = new Long[n]; for (int i = 0; i < n; i++) b[i] = src[i];
                t0 = System.nanoTime(); Arrays.sort(b); long tim = ms(t0);
                System.out.printf("%-6s 手写快排 %5d   Arrays.sort(long[]) %5d   parallelSort %5d   Arrays.sort(Long[]) TimSort %5d%n",
                        name, q, d, p, tim);
            }
        }
    }

    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }
}
