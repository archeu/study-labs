package labs.algo.two_pointers;

import java.util.Arrays;
import java.util.Random;

/** 页面 ⑥ 的坑。 */
public class Pitfalls {
    public static void main(String[] args) {
        negatives();
        ifInsteadOfWhile();
        intOverflow();
        bench();
    }

    /** 坑 1：有负数（净流入 = 买 - 卖），滑动窗口给出错答案。 */
    static void negatives() {
        System.out.println("== 坑 1：有负数");
        long[] net = {5, -3, 1, 6, -10, 8, 4};
        int[] w = Windows.shortestAtLeast(net, 12, false);
        int[] b = Windows.shortestAtLeastBrute(net, 12);
        System.out.printf("净流入 %s，最短一段合计 >= 12：双指针 长度 %d 起点 %d；暴力 长度 %d 起点 %d%n%n",
                Arrays.toString(net), w[0], w[1], b[0], b[1]);
    }

    /** 坑 2：缩窗口写成 if，只缩一格。 */
    static void ifInsteadOfWhile() {
        System.out.println("== 坑 2：缩窗口用 if");
        long[] qty = {1, 1, 1, 1, 20, 1};
        int best = Integer.MAX_VALUE, l = 0; long sum = 0;
        for (int r = 0; r < qty.length; r++) {
            sum += qty[r];
            if (sum >= 20) { best = Math.min(best, r - l + 1); sum -= qty[l++]; }   // 错：应为 while
        }
        System.out.printf("%s 合计 >= 20：if 版本得到 %d，while 版本得到 %d%n%n", Arrays.toString(qty), best,
                Windows.shortestAtLeast(qty, 20, false)[0]);
    }

    /** 坑 3：成交额（价格分 × 数量）用 int 累加。 */
    static void intOverflow() {
        System.out.println("== 坑 3：int 累加成交额");
        int sumInt = 0; long sumLong = 0;
        for (int i = 0; i < 1000; i++) { int amount = 6_000_000 * 1; sumInt += amount; sumLong += amount; }  // 60000.00 元 × 1 手，1000 笔
        System.out.printf("1000 笔 60000.00 元：int 合计 %d 分，long 合计 %d 分%n%n", sumInt, sumLong);
    }

    /** 双指针 vs 暴力：n 笔成交找最短放量段。 */
    static void bench() {
        System.out.println("== 测速");
        Random r = new Random(9);
        for (int n : new int[]{20_000, 100_000}) {
            long[] q = new long[n];
            for (int i = 0; i < n; i++) q[i] = 1 + r.nextInt(10);
            q[n / 2] = 900;                                   // 一笔大单
            long k = 2L * n;                                  // 窗口大约要 0.36n 笔
            for (int round = 1; round <= 2; round++) {
                long t0 = System.nanoTime(); int[] a = Windows.shortestAtLeast(q, k, false); long tw = System.nanoTime() - t0;
                t0 = System.nanoTime(); int[] b = Windows.shortestAtLeastBrute(q, k); long tb = System.nanoTime() - t0;
                System.out.printf("n=%,d 第 %d 轮：双指针 %.2f ms，暴力 %.0f ms，结果一致 %s（长度 %d）%n",
                        n, round, tw / 1e6, tb / 1e6, Arrays.equals(a, b), a[0]);
            }
        }
    }
}
