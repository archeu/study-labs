package labs.algo.binary_search;

import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 当天 3000 万笔成交（时间戳毫秒，有序），随机查 1000 个时间段的起点：从头扫 vs 二分。
 * 再看 Collections.binarySearch 用在 LinkedList 上。
 * java -Xmx2g -cp algo/binary-search/target/classes labs.algo.binary_search.SearchBench
 */
public class SearchBench {
    public static void main(String[] args) {
        int n = 30_000_000;
        long[] t = new long[n];
        Random r = new Random(3);
        long cur = 9 * 3600_000L;
        for (int i = 0; i < n; i++) { cur += r.nextInt(2); t[i] = cur; }   // 平均每毫秒 0.5 笔，有重复
        long[] q = new long[1000];
        for (int i = 0; i < q.length; i++) q[i] = t[0] + (long) (r.nextDouble() * (t[n - 1] - t[0]));

        for (int round = 1; round <= 3; round++) {
            long s1 = 0, st = System.nanoTime();
            for (long x : q) { int i = 0; while (i < n && t[i] < x) i++; s1 += i; }
            long linear = System.nanoTime() - st;
            long s2 = 0; st = System.nanoTime();
            for (long x : q) s2 += TradeLog.lowerBound(t, x);
            long bin = System.nanoTime() - st;
            System.out.printf("第 %d 轮  从头扫：每次 %.2f ms   二分：每次 %.0f ns   结果一致：%s%n",
                    round, linear / 1e6 / q.length, bin / (double) q.length, s1 == s2);
        }

        int m = 200_000;
        List<Long> arr = new ArrayList<>(m), linked = new LinkedList<>();
        for (int i = 0; i < m; i++) { arr.add((long) i * 2); linked.add((long) i * 2); }
        for (int round = 1; round <= 2; round++) {
            long st = System.nanoTime(); long s = 0;
            for (int k = 0; k < 1000; k++) s += Collections.binarySearch(arr, (long) r.nextInt(2 * m));
            long a = System.nanoTime() - st;
            st = System.nanoTime();
            for (int k = 0; k < 1000; k++) s += Collections.binarySearch(linked, (long) r.nextInt(2 * m));
            long l = System.nanoTime() - st;
            System.out.printf("20 万个元素  Collections.binarySearch  ArrayList 每次 %.0f ns   LinkedList 每次 %.0f ns %s%n",
                    a / 1000.0, l / 1000.0, s == 42 ? "" : "");
        }
    }
}
