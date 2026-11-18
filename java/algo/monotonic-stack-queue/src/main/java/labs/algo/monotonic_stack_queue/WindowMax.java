package labs.algo.monotonic_stack_queue;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 过去 windowMs 毫秒内的最高成交价：单调队列。
 * 队列里从头到尾：时间越来越晚，价格严格越来越低。队头就是窗口最高价。
 * 价格用 long 存“分”（第 1 课）。
 */
public class WindowMax {
    private final long windowMs;
    // 每个元素是 {时间毫秒, 价格分}；不能只存价格，否则不知道它什么时候过期
    private final Deque<long[]> dq = new ArrayDeque<>();

    public WindowMax(long windowMs) { this.windowMs = windowMs; }

    /** 来了一笔成交：从队尾弹掉所有“不比它高”的，再把它放进队尾。 */
    public void onTrade(long timeMs, long price) {
        while (!dq.isEmpty() && dq.peekLast()[1] <= price) {
            dq.pollLast();               // 它比新来的早、又不比新来的高：以后再也当不上最高价
        }
        dq.offerLast(new long[]{timeMs, price});
    }

    /** 现在是 nowMs，窗口 (nowMs - windowMs, nowMs] 里的最高价；窗口里没有成交时返回 -1。 */
    public long max(long nowMs) {
        while (!dq.isEmpty() && dq.peekFirst()[0] <= nowMs - windowMs) {
            dq.pollFirst();              // 队头过期了：按时间滑，不是按笔数滑
        }
        return dq.isEmpty() ? -1 : dq.peekFirst()[1];
    }

    String dump() {
        StringBuilder sb = new StringBuilder("[");
        for (long[] e : dq) {
            if (sb.length() > 1) sb.append(' ');
            sb.append(e[1]).append('@').append(e[0] / 1000).append('s');
        }
        return sb.append(']').toString();
    }

    public static void main(String[] args) {
        // 和页面 ④ 的分步动画同一组数据：窗口 3 秒，每秒一笔，价格单位“元”方便看
        long[] prices = {101, 103, 102, 99, 100, 98, 97, 104};
        WindowMax w = new WindowMax(3000);
        for (int i = 0; i < prices.length; i++) {
            long t = (i + 1) * 1000L;
            w.onTrade(t, prices[i]);
            long m = w.max(t);
            System.out.printf("第%ds 成交 %d  队列 %-28s 过去 3 秒最高 %d%n", i + 1, prices[i], w.dump(), m);
        }
        // 冷清：最后一笔在第 8 秒，第 12 秒再问
        System.out.printf("第12s 没有新成交  过去 3 秒最高 %d（-1 = 窗口里没有成交）%n", w.max(12000));
    }
}
