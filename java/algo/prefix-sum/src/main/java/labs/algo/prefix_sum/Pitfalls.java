package labs.algo.prefix_sum;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** 页面 ⑥ 的坑，加一个测速。 */
public class Pitfalls {
    public static void main(String[] args) {
        forgetZero();
        intPrefix();
        doubleDrift();
        bench();
    }

    /** 坑 3：前缀和 + 哈希，忘了放 {0: 1}。 */
    static void forgetZero() {
        System.out.println("== 坑 3：忘了空前缀");
        long[] net = {3, -1, -2, 5, -3, 3, 0};
        Map<Long, Integer> seen = new HashMap<>();
        long p = 0, count = 0;
        for (long x : net) { p += x; count += seen.getOrDefault(p, 0); seen.merge(p, 1, Integer::sum); }
        System.out.printf("合计为 0 的段：漏了 {0:1} 得到 %d，正确是 %d%n%n", count, VolumeIndex.countSumEquals(net, 0));
    }

    /** 坑 2：前缀和数组用 int。 */
    static void intPrefix() {
        System.out.println("== 坑 2：int 前缀和");
        int[] p = new int[86_401]; long[] q = new long[86_401];
        for (int s = 0; s < 86_400; s++) { int v = 30_000; p[s + 1] = p[s] + v; q[s + 1] = q[s] + v; }   // 每秒 3 万手
        System.out.printf("全天成交量：int 前缀和 %d，long 前缀和 %d%n", p[86_400], q[86_400]);
        System.out.printf("[09:00, 10:00)：int 相减 %d，long 相减 %d（同一个区间，int 溢出后相减有时又是对的）%n%n",
                p[36_000] - p[32_400], q[36_000] - q[32_400]);
    }

    /** 坑 4：价格用 double 做前缀和，相减有误差。 */
    static void doubleDrift() {
        System.out.println("== 坑 4：double 前缀和");
        int n = 1_000_000;
        double[] p = new double[n + 1];
        for (int i = 0; i < n; i++) p[i + 1] = p[i] + 0.1;            // 每笔 0.1 元手续费
        double last10 = p[n] - p[n - 10];
        System.out.printf("最后 10 笔手续费：double 前缀和相减 = %.15f（应为 1.0）%n%n", last10);
    }

    /** 每秒一个桶，全天 86400 秒，随机查 100 万个时段：每次扫 vs 前缀和。 */
    static void bench() {
        System.out.println("== 测速");
        Random r = new Random(4);
        long[] vol = new long[86_400];
        for (int i = 0; i < vol.length; i++) vol[i] = r.nextInt(50_000);
        int q = 1_000_000;
        int[] from = new int[q], to = new int[q];
        for (int i = 0; i < q; i++) { int a = r.nextInt(86_400), b = r.nextInt(86_400); from[i] = Math.min(a, b); to[i] = Math.max(a, b) + 1; }
        for (int round = 1; round <= 3; round++) {
            long t0 = System.nanoTime(), s1 = 0;
            for (int i = 0; i < q; i++) for (int t = from[i]; t < to[i]; t++) s1 += vol[t];
            long scan = System.nanoTime() - t0;
            t0 = System.nanoTime();
            VolumeIndex idx = new VolumeIndex(vol);
            long build = System.nanoTime() - t0;
            t0 = System.nanoTime(); long s2 = 0;
            for (int i = 0; i < q; i++) s2 += idx.volume(from[i], to[i]);
            long pre = System.nanoTime() - t0;
            System.out.printf("第 %d 轮：每次扫 %.0f ms（每次 %.1f µs）  前缀和 建 %.2f ms + 查 %.1f ms（每次 %.1f ns）  一致 %s%n",
                    round, scan / 1e6, scan / 1e3 / q, build / 1e6, pre / 1e6, pre / (double) q, s1 == s2);
        }
    }
}
