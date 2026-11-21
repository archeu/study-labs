package labs.algo.dp_knapsack;

import java.util.Arrays;
import java.util.Random;

/**
 * 第 22 课 · 0-1 背包。全景第 12 站：100 万资金分给几个策略，每个策略有最低资金要求和预期收益，
 * 每个策略要么按最低资金投、要么不投，总收益最大是多少？
 */
public class Allocate {

    /** 二维表：dp[i][w] = 只考虑前 i 个策略、预算 w 时的最大收益。 */
    static long twoD(int[] cost, int[] gain, int W) {
        int n = cost.length;
        long[][] dp = new long[n + 1][W + 1];
        for (int i = 1; i <= n; i++)
            for (int w = 0; w <= W; w++) {
                dp[i][w] = dp[i - 1][w];                                       // 不投第 i 个
                if (w >= cost[i - 1])
                    dp[i][w] = Math.max(dp[i][w], dp[i - 1][w - cost[i - 1]] + gain[i - 1]); // 投
            }
        return dp[n][W];
    }

    /** 一维 + 倒序：每个策略最多用一次。 */
    static long oneD(int[] cost, int[] gain, int W, boolean print) {
        long[] dp = new long[W + 1];
        for (int i = 0; i < cost.length; i++) {
            for (int w = W; w >= cost[i]; w--) dp[w] = Math.max(dp[w], dp[w - cost[i]] + gain[i]);
            if (print) System.out.println("  加入策略 " + (char) ('A' + i) + " 后 dp = " + Arrays.toString(dp));
        }
        return dp[W];
    }

    /** 坑：一维正序，同一个策略会被投好几次（变成了完全背包）。 */
    static long oneDForward(int[] cost, int[] gain, int W) {
        long[] dp = new long[W + 1];
        for (int i = 0; i < cost.length; i++)
            for (int w = cost[i]; w <= W; w++) dp[w] = Math.max(dp[w], dp[w - cost[i]] + gain[i]);
        return dp[W];
    }

    /** 贪心：按“收益 / 资金”从高到低，放得下就投。 */
    static long greedyRatio(int[] cost, int[] gain, int W) {
        Integer[] idx = new Integer[cost.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare((double) gain[b] / cost[b], (double) gain[a] / cost[a]));
        long s = 0; int left = W;
        for (int i : idx) if (cost[i] <= left) { left -= cost[i]; s += gain[i]; }
        return s;
    }

    static long brute(int[] cost, int[] gain, int W) {
        long best = 0;
        for (int m = 0; m < (1 << cost.length); m++) {
            long c = 0, g = 0;
            for (int i = 0; i < cost.length; i++) if ((m >> i & 1) == 1) { c += cost[i]; g += gain[i]; }
            if (c <= W) best = Math.max(best, g);
        }
        return best;
    }

    /** 完全背包计数：面额 coins 凑 amount 有多少种（不计顺序）。正序。 */
    static long ways(int[] coins, int amount) {
        long[] f = new long[amount + 1];
        f[0] = 1;
        for (int c : coins) for (int a = c; a <= amount; a++) f[a] += f[a - c];
        return f[amount];
    }

    public static void main(String[] args) {
        // 单位：10 万元。预算 10 = 100 万
        int[] cost = {6, 5, 5};
        int[] gain = {30, 20, 18};
        System.out.println("策略 A(6,30) B(5,20) C(5,18)，预算 10：");
        long v = oneD(cost, gain, 10, true);
        System.out.println("0-1 背包 = " + v + "，按收益率贪心 = " + greedyRatio(cost, gain, 10)
                + "，一维正序（错）= " + oneDForward(cost, gain, 10));

        Random r = new Random(22);
        int bad = 0, greedyWrong = 0;
        for (int t = 0; t < 2000; t++) {
            int n = 1 + r.nextInt(12), W = 1 + r.nextInt(40);
            int[] c = new int[n], g = new int[n];
            for (int i = 0; i < n; i++) { c[i] = 1 + r.nextInt(15); g[i] = 1 + r.nextInt(50); }
            long b = brute(c, g, W);
            if (twoD(c, g, W) != b || oneD(c, g, W, false) != b) bad++;
            if (greedyRatio(c, g, W) != b) greedyWrong++;
        }
        System.out.println("对拍 2000 组：二维 / 一维和暴力不一致 " + bad + " 组；按收益率贪心错 " + greedyWrong + " 组");

        // 规模：O(n * W)
        int n = 200;
        int[] c = new int[n], g = new int[n];
        for (int i = 0; i < n; i++) { c[i] = 1 + r.nextInt(1000); g[i] = 1 + r.nextInt(1000); }
        for (int W : new int[]{10000, 100000, 1000000}) {
            long t0 = System.nanoTime();
            long ans = oneD(c, g, W, false);
            System.out.println("n=200，预算 " + W + " 份：" + (System.nanoTime() - t0) / 1000000 + " ms，数组 "
                    + (W + 1) * 8L / 1024 + " KB，最优 " + ans);
        }
        System.out.println("面额 {1,3,4} 凑 6 的方法数 = " + ways(new int[]{1, 3, 4}, 6)
                + "；人民币 {1,2,5,10,20,50,100} 凑 100 = " + ways(new int[]{1, 2, 5, 10, 20, 50, 100}, 100));
    }
}
