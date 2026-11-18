package labs.algo.prefix_sum;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 前缀和与差分，都在成交和挂单上：
 * 1 每秒一个桶的成交量，前缀和数组 P，任意时段 [from, to) 成交量 = P[to] - P[from]
 * 2 差分：每张挂单活在 [挂出, 成交或撤单) 这段时间，求每一秒有多少张挂单
 * 3 前缀和 + 哈希：净流入合计恰好为 K 的连续时段有几个
 */
public class VolumeIndex {
    final long[] prefix;                 // prefix[i] = 第 0..i-1 秒的成交量合计；长度 n + 1，prefix[0] = 0

    public VolumeIndex(long[] perSecond) {
        prefix = new long[perSecond.length + 1];
        for (int i = 0; i < perSecond.length; i++) prefix[i + 1] = prefix[i] + perSecond[i];
    }

    /** [from, to) 秒的成交量。 */
    public long volume(int from, int to) { return prefix[to] - prefix[from]; }

    /** 差分：orders[k] = {挂出秒, 结束秒}，活在 [start, end)。返回每一秒的挂单数。 */
    public static int[] activeOrders(int[][] orders, int seconds, boolean trace) {
        int[] diff = new int[seconds + 1];               // 多一格，放 end = seconds 的那个 -1
        for (int[] o : orders) {
            diff[o[0]]++;                                // 从 start 起多一张
            diff[o[1]]--;                                // 从 end 起少一张
            if (trace) System.out.printf("挂单 [%d,%d)：diff[%d]++，diff[%d]--  diff=%s%n", o[0], o[1], o[0], o[1], Arrays.toString(diff));
        }
        int[] active = new int[seconds];
        int run = 0;
        for (int t = 0; t < seconds; t++) { run += diff[t]; active[t] = run; }   // 差分的前缀和 = 原数组
        return active;
    }

    /** 前缀和 + 哈希：合计恰好为 k 的连续段有几个（元素可正可负）。 */
    public static long countSumEquals(long[] a, long k) {
        Map<Long, Integer> seen = new HashMap<>();
        seen.put(0L, 1);                                 // 空前缀：让从下标 0 开始的段也能被数到
        long p = 0, count = 0;
        for (long x : a) {
            p += x;
            count += seen.getOrDefault(p - k, 0);        // 之前有几个前缀等于 p - k，就有几段以这里结尾、合计为 k
            seen.merge(p, 1, Integer::sum);
        }
        return count;
    }

    public static void main(String[] args) {
        long[] vol = {12, 0, 7, 30, 5, 5, 18, 2, 0, 9};          // 10 秒，每秒的成交量
        VolumeIndex idx = new VolumeIndex(vol);
        System.out.println("每秒成交量 " + Arrays.toString(vol));
        System.out.println("前缀和     " + Arrays.toString(idx.prefix));
        System.out.printf("[3, 7) 秒成交量 = P[7] - P[3] = %d - %d = %d%n%n", idx.prefix[7], idx.prefix[3], idx.volume(3, 7));

        int[][] orders = {{1, 4}, {2, 6}, {3, 5}, {5, 9}, {7, 8}};
        int[] active = activeOrders(orders, 10, true);
        System.out.println("每秒挂单数 " + Arrays.toString(active));
        int peak = 0; for (int t = 1; t < active.length; t++) if (active[t] > active[peak]) peak = t;
        System.out.printf("峰值：第 %d 秒，%d 张%n%n", peak, active[peak]);

        long[] net = {3, -1, -2, 5, -3, 3, 0};
        System.out.println("净流入 " + Arrays.toString(net) + "，合计恰好为 0 的连续段有 " + countSumEquals(net, 0) + " 个");
    }
}
