package labs.algo.monotonic_stack_queue;

import java.util.ArrayDeque;
import java.util.Deque;

/** 页面 ⑥ 的几个坑，每个都跑出来看。 */
public class Pitfalls {

    public static void main(String[] args) {
        countVsTime();
        equalPrices();
        outOfOrder();
    }

    /** 坑 1：按笔数滑而不是按时间滑。冷清时最高价停在很久以前。 */
    static void countVsTime() {
        System.out.println("== 坑 1：按笔数滑 vs 按时间滑（窗口 = 60 秒；按笔数的版本取“最近 3 笔”）");
        // 09:00:00 一笔 120，然后冷清了 10 分钟，09:10:00 来两笔 100、101
        long[][] trades = {{0, 120}, {600_000, 100}, {600_001, 101}};
        WindowMax byTime = new WindowMax(60_000);
        Deque<long[]> byCount = new ArrayDeque<>();
        for (long[] tr : trades) {
            byTime.onTrade(tr[0], tr[1]);
            while (!byCount.isEmpty() && byCount.peekLast()[1] <= tr[1]) byCount.pollLast();
            byCount.offerLast(new long[]{tr[0], tr[1], 0});
        }
        // 按笔数：最近 3 笔里最高 → 120（10 分钟前的价）
        long countMax = byCount.peekFirst()[1];
        System.out.printf("09:10:00 按最近 3 笔：最高 %d  ← 10 分钟前的价%n", countMax);
        System.out.printf("09:10:00 按过去 60 秒：最高 %d%n%n", byTime.max(600_001));
    }

    /** 坑 2：相等价格弹不弹。队列里弹 <= 和弹 < 结果都对、长度不同；单调栈里弹 <= 就把“≥”当成了“更高”。 */
    static void equalPrices() {
        System.out.println("== 坑 2：相等价格");
        long[] flat = new long[10];
        java.util.Arrays.fill(flat, 100);
        Deque<Long> le = new ArrayDeque<>(), lt = new ArrayDeque<>();
        for (long p : flat) {
            while (!le.isEmpty() && le.peekLast() <= p) le.pollLast();
            le.offerLast(p);
            while (!lt.isEmpty() && lt.peekLast() < p) lt.pollLast();
            lt.offerLast(p);
        }
        System.out.printf("10 笔都是 100：弹 <= 时队列长 %d，弹 < 时队列长 %d（最高价都是 100）%n", le.size(), lt.size());

        long[] ps = {100, 100, 101};
        int[] wrong = new int[]{-1, -1, -1};
        int[] st = new int[3]; int top = 0;
        for (int i = 0; i < 3; i++) {
            while (top > 0 && ps[st[top - 1]] <= ps[i]) wrong[st[--top]] = i;   // 错：<= 把相等也当成了更高
            st[top++] = i;
        }
        int[] right = NextHigher.nextHigher(ps, false);
        System.out.printf("价格 100 100 101，第 0 笔的“下一个更高价”：弹 <= 得到第 %d 笔（%d，错），弹 < 得到第 %d 笔（%d）%n%n",
                wrong[0], ps[wrong[0]], right[0], ps[right[0]]);
    }

    /** 坑 3：时间戳乱序。单调队列默认后来的不早于先来的。 */
    static void outOfOrder() {
        System.out.println("== 坑 3：迟到的成交");
        WindowMax w = new WindowMax(3000);
        w.onTrade(10_000, 105);
        w.onTrade(6_000, 103);     // 迟到 4 秒的一笔，本该早已过期
        System.out.printf("第 10 秒问最高：%d（对）%n", w.max(10_000));
        WindowMax w3 = new WindowMax(3000);
        w3.onTrade(10_000, 105);
        w3.onTrade(6_000, 108);    // 先到 105，后到迟到的 108：108 把 105 弹掉，自己排在队头
        System.out.printf("先到 105@10s、再到迟到的 108@6s，第 10 秒问最高：%d（错，应该是 105：它被一笔早就过期的 108 弹掉了）%n",
                w3.max(10_000));
    }
}
