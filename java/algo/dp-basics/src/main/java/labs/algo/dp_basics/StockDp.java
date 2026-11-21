package labs.algo.dp_basics;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 第 21 课 · 动态规划入门。全景第 12 站“回测”：带手续费、带冷冻期的买卖。
 * 1) 纯递归（回溯）→ 记忆化 → 递推表 → 两个变量，同一个状态 (day, holding)
 * 2) 和暴力对拍；和第 19 课的朴素贪心对比
 * 3) 冷冻期多一个状态；更新顺序写错的后果
 * 4) 记忆化递归在 n = 10 万时栈溢出
 */
public class StockDp {

    static long calls;

    /** 纯递归：从第 day 天开始、手里 holding 时，之后最多还能赚多少。 */
    static long rec(int[] p, int fee, int day, int holding) {
        calls++;
        if (day == p.length) return 0;
        long stay = rec(p, fee, day + 1, holding);
        long act = holding == 1 ? p[day] - fee + rec(p, fee, day + 1, 0)
                                : -p[day] + rec(p, fee, day + 1, 1);
        return Math.max(stay, act);
    }

    /** 记忆化：同一个 (day, holding) 只算一次。 */
    static long memo(int[] p, int fee, int day, int holding, Map<Long, Long> cache) {
        calls++;
        if (day == p.length) return 0;
        long key = (long) day * 2 + holding;
        Long hit = cache.get(key);
        if (hit != null) return hit;
        long stay = memo(p, fee, day + 1, holding, cache);
        long act = holding == 1 ? p[day] - fee + memo(p, fee, day + 1, 0, cache)
                                : -p[day] + memo(p, fee, day + 1, 1, cache);
        long r = Math.max(stay, act);
        cache.put(key, r);
        return r;
    }

    /** 递推表：cash[i] = 第 i 天收盘空仓时的最多现金，hold[i] = 持有一份时的。 */
    static long table(int[] p, int fee, boolean print) {
        int n = p.length;
        long[] cash = new long[n], hold = new long[n];
        cash[0] = 0;
        hold[0] = -p[0];
        for (int i = 1; i < n; i++) {
            cash[i] = Math.max(cash[i - 1], hold[i - 1] + p[i] - fee);
            hold[i] = Math.max(hold[i - 1], cash[i - 1] - p[i]);
        }
        if (print) {
            System.out.println("  cash = " + Arrays.toString(cash));
            System.out.println("  hold = " + Arrays.toString(hold));
        }
        return cash[n - 1];
    }

    /** 只留两个变量。 */
    static long rolling(int[] p, int fee) {
        long cash = 0, hold = -p[0];
        for (int i = 1; i < p.length; i++) {
            long c = Math.max(cash, hold + p[i] - fee);
            long h = Math.max(hold, cash - p[i]);
            cash = c; hold = h;
        }
        return cash;
    }

    /** 第 19 课的朴素贪心：每段涨幅大于手续费就做一次。 */
    static long naive(int[] p, int fee) {
        long s = 0;
        for (int i = 1; i < p.length; i++) { int d = p[i] - p[i - 1]; if (d > fee) s += d - fee; }
        return s;
    }

    /** 冷冻期：卖出的第二天不能买。三个状态：持有、今天刚卖、空仓可买。 */
    static long cooldown(int[] p) {
        long hold = -p[0], sold = Long.MIN_VALUE / 4, rest = 0;
        for (int i = 1; i < p.length; i++) {
            long h = Math.max(hold, rest - p[i]);
            long s = hold + p[i];
            long r = Math.max(rest, sold);
            hold = h; sold = s; rest = r;
        }
        return Math.max(sold, rest);
    }

    /** 错误：原地更新，rest 先更新，hold 用到了“今天刚卖完就变成可买”的 rest。 */
    static long cooldownWrongOrder(int[] p) {
        long hold = -p[0], sold = Long.MIN_VALUE / 4, rest = 0;
        for (int i = 1; i < p.length; i++) {
            sold = hold + p[i];
            rest = Math.max(rest, sold);
            hold = Math.max(hold, rest - p[i]);
        }
        return Math.max(sold, rest);
    }

    /** 冷冻期的暴力。 */
    static long bruteCooldown(int[] p, int day, int state) { // 0 可买，1 持有，2 冷冻
        if (day == p.length) return 0;
        long best = bruteCooldown(p, day + 1, state == 2 ? 0 : state);
        if (state == 0) best = Math.max(best, -p[day] + bruteCooldown(p, day + 1, 1));
        if (state == 1) best = Math.max(best, p[day] + bruteCooldown(p, day + 1, 2));
        return best;
    }

    public static void main(String[] args) {
        int[] p = {1, 3, 2, 8, 4, 9};
        int fee = 2;
        System.out.println("价格 " + Arrays.toString(p) + "，手续费 " + fee);
        System.out.println("第 19 课朴素贪心 = " + naive(p, fee));
        calls = 0;
        long a = rec(p, fee, 0, 0);
        System.out.println("纯递归 = " + a + "，调用 " + calls + " 次");
        calls = 0;
        long b = memo(p, fee, 0, 0, new HashMap<Long, Long>());
        System.out.println("记忆化 = " + b + "，调用 " + calls + " 次");
        System.out.println("递推表 = " + table(p, fee, true));
        System.out.println("两个变量 = " + rolling(p, fee));

        // 对拍 + 调用次数随 n 增长
        Random r = new Random(21);
        int bad = 0;
        for (int t = 0; t < 2000; t++) {
            int[] q = new int[1 + r.nextInt(14)];
            for (int i = 0; i < q.length; i++) q[i] = 90 + r.nextInt(21);
            int f = r.nextInt(5);
            calls = 0;
            if (rolling(q, f) != rec(q, f, 0, 0)) bad++;
        }
        System.out.println("对拍 2000 组（手续费 0~4）：不一致 " + bad + " 组");
        for (int n : new int[]{10, 20, 25}) {
            int[] q = new int[n];
            for (int i = 0; i < n; i++) q[i] = 90 + r.nextInt(21);
            calls = 0; rec(q, 2, 0, 0); long c1 = calls;
            calls = 0; memo(q, 2, 0, 0, new HashMap<Long, Long>()); long c2 = calls;
            System.out.println("n=" + n + "：纯递归调用 " + c1 + " 次，记忆化 " + c2 + " 次");
        }

        int[] cd = {1, 2, 3, 0, 2};
        System.out.println("冷冻期 " + Arrays.toString(cd) + "：三状态 = " + cooldown(cd)
                + "，暴力 = " + bruteCooldown(cd, 0, 0) + "，原地更新顺序写错 = " + cooldownWrongOrder(cd));
        int bad2 = 0;
        for (int t = 0; t < 2000; t++) {
            int[] q = new int[1 + r.nextInt(14)];
            for (int i = 0; i < q.length; i++) q[i] = 90 + r.nextInt(21);
            if (cooldown(q) != bruteCooldown(q, 0, 0)) bad2++;
        }
        System.out.println("冷冻期对拍 2000 组：不一致 " + bad2 + " 组");

        // 分钟级：36 万个价格点
        int n = 360000;
        int[] big = new int[n];
        big[0] = 10000;
        for (int i = 1; i < n; i++) big[i] = Math.max(1, big[i - 1] + r.nextInt(21) - 10);
        long t0 = System.nanoTime();
        long v = rolling(big, 5);
        System.out.println("36 万个分钟价：两个变量 " + (System.nanoTime() - t0) / 1000000 + " ms，结果 " + v);
        try {
            memo(big, 5, 0, 0, new HashMap<Long, Long>());
            System.out.println("记忆化递归：跑完了");
        } catch (StackOverflowError e) {
            System.out.println("记忆化递归：StackOverflowError");
        }
    }
}
