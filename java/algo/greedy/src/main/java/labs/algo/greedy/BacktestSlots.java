package labs.algo.greedy;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * 区间调度：一台回测机，一天里有一堆回测任务 [开始, 结束)，互不重叠地最多能排几个？
 * 四条“看起来都对”的贪心规则，和暴力枚举（2^n）对拍，数一数各错了多少次。
 */
public class BacktestSlots {

    static int greedy(int[][] jobs, Comparator<int[]> rule) {
        int[][] a = jobs.clone();
        Arrays.sort(a, rule);
        int count = 0;
        // 注意：对“最早开始”“最短”这些规则，排好序后依次能放就放
        boolean[] taken = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            boolean ok = true;
            for (int j = 0; j < i; j++)
                if (taken[j] && a[j][0] < a[i][1] && a[i][0] < a[j][1]) { ok = false; break; }
            if (ok) { taken[i] = true; count++; }
        }
        return count;
    }

    /** 最早结束：排好序后只要和上一个选中的不重叠就选，O(n log n)。 */
    static int earliestEnd(int[][] jobs) {
        int[][] a = jobs.clone();
        Arrays.sort(a, (x, y) -> Integer.compare(x[1], y[1]));
        int count = 0, end = Integer.MIN_VALUE;
        for (int[] j : a) if (j[0] >= end) { count++; end = j[1]; }
        return count;
    }

    static int brute(int[][] a) {
        int n = a.length, best = 0;
        for (int mask = 0; mask < (1 << n); mask++) {
            int k = Integer.bitCount(mask);
            if (k <= best) continue;
            boolean ok = true;
            for (int i = 0; i < n && ok; i++) if ((mask >> i & 1) == 1)
                for (int j = i + 1; j < n; j++) if ((mask >> j & 1) == 1)
                    if (a[i][0] < a[j][1] && a[j][0] < a[i][1]) { ok = false; break; }
            if (ok) best = k;
        }
        return best;
    }

    static int conflicts(int[][] a, int[] x) {
        int c = 0;
        for (int[] y : a) if (y != x && x[0] < y[1] && y[0] < x[1]) c++;
        return c;
    }

    public static void main(String[] args) {
        Random r = new Random(1919);
        int trials = 10000;
        int failStart = 0, failShort = 0, failFewest = 0, failEnd = 0;
        for (int t = 0; t < trials; t++) {
            int n = 4 + r.nextInt(9);
            final int[][] jobs = new int[n][];
            for (int i = 0; i < n; i++) {
                int s = r.nextInt(24), len = 1 + r.nextInt(8);
                jobs[i] = new int[]{s, s + len};
            }
            int opt = brute(jobs);
            if (greedy(jobs, (x, y) -> Integer.compare(x[0], y[0])) < opt) failStart++;
            if (greedy(jobs, (x, y) -> Integer.compare(x[1] - x[0], y[1] - y[0])) < opt) failShort++;
            if (greedy(jobs, (x, y) -> Integer.compare(conflicts(jobs, x), conflicts(jobs, y))) < opt) failFewest++;
            if (earliestEnd(jobs) != opt) failEnd++;
        }
        System.out.println("随机 " + trials + " 组（每组 4~12 个任务，0~32 点），比最优少排的次数：");
        System.out.println("  最早开始   " + failStart);
        System.out.println("  最短的先排 " + failShort);
        System.out.println("  冲突最少   " + failFewest);
        System.out.println("  最早结束   " + failEnd);

        int[][] demo = {{9, 12}, {10, 11}, {11, 14}, {13, 15}, {12, 13}, {14, 17}, {16, 18}};
        System.out.println("页面上那 7 个任务：最早结束选出 " + earliestEnd(demo) + " 个，暴力最优 " + brute(demo));
    }
}
