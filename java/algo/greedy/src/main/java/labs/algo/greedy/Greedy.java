package labs.algo.greedy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 第 19 课 · 贪心。全景第 12 站“回测”：拿今天的价格，策略最多能赚多少。
 * 1) 不限次数买卖：把每一段上涨都吃下（贪心），和暴力枚举对拍
 * 2) 只许买卖一次：边走边记最低价
 * 3) 加手续费：贪心不再对（第 21 课用 DP 解）
 * 4) 面额 {1,3,4} 凑 6：先拿最大的不对
 */
public class Greedy {

    /** 不限次数：每一天只要比前一天涨，就把这一段差价拿下。 */
    static long maxProfitUnlimited(int[] p) {
        long sum = 0;
        for (int i = 1; i < p.length; i++) {
            if (p[i] > p[i - 1]) sum += p[i] - p[i - 1];
        }
        return sum;
    }

    /** 只许一次：走到第 i 天时，拿“今天价 - 之前最低价”更新答案。 */
    static long maxProfitOnce(int[] p) {
        long best = 0;
        int min = Integer.MAX_VALUE;
        for (int x : p) {
            min = Math.min(min, x);
            best = Math.max(best, (long) x - min);
        }
        return best;
    }

    /** 暴力：每天三种动作（不动 / 买 / 卖），把所有走法都试一遍。O(2^n) 级，只用来对拍。 */
    static long bruteUnlimited(int[] p, int day, boolean holding, long cash, int fee) {
        if (day == p.length) return holding ? Long.MIN_VALUE / 4 : cash;
        long best = bruteUnlimited(p, day + 1, holding, cash, fee);            // 不动
        if (holding) best = Math.max(best, bruteUnlimited(p, day + 1, false, cash + p[day] - fee, fee)); // 卖
        else best = Math.max(best, bruteUnlimited(p, day + 1, true, cash - p[day], fee));                // 买
        return best;
    }

    /** 加手续费后“朴素贪心”：每段涨幅只要大于手续费就做一次。错的。 */
    static long naiveGreedyWithFee(int[] p, int fee) {
        long sum = 0;
        for (int i = 1; i < p.length; i++) {
            int d = p[i] - p[i - 1];
            if (d > fee) sum += d - fee;
        }
        return sum;
    }

    /** 凑钱：每次先拿面额最大的。 */
    static int coinGreedy(int[] coins, int amount, List<Integer> used) {
        int[] c = coins.clone();
        Arrays.sort(c);
        int n = 0;
        for (int i = c.length - 1; i >= 0; i--) {
            while (amount >= c[i]) { amount -= c[i]; used.add(c[i]); n++; }
        }
        return amount == 0 ? n : -1;
    }

    /** 凑钱的正确答案：把 0..amount 每个金额的最少枚数都算出来（第 22 课的完全背包）。 */
    static int coinOptimal(int[] coins, int amount) {
        int[] f = new int[amount + 1];
        Arrays.fill(f, Integer.MAX_VALUE / 2);
        f[0] = 0;
        for (int a = 1; a <= amount; a++)
            for (int c : coins) if (c <= a) f[a] = Math.min(f[a], f[a - c] + 1);
        return f[amount];
    }

    public static void main(String[] args) {
        int[] btc = {100, 98, 103, 101, 106, 104, 108};
        System.out.println("价格 " + Arrays.toString(btc));
        System.out.println("不限次数（贪心）= " + maxProfitUnlimited(btc)
                + "，暴力枚举 = " + bruteUnlimited(btc, 0, false, 0, 0));
        System.out.println("只许一次 = " + maxProfitOnce(btc));

        // 对拍：2000 组随机价格，长度 1..14
        Random r = new Random(19);
        int mismatch = 0;
        for (int t = 0; t < 2000; t++) {
            int[] p = new int[1 + r.nextInt(14)];
            for (int i = 0; i < p.length; i++) p[i] = 90 + r.nextInt(21);
            if (maxProfitUnlimited(p) != bruteUnlimited(p, 0, false, 0, 0)) mismatch++;
        }
        System.out.println("对拍 2000 组：贪心和暴力不一致 " + mismatch + " 组");

        int[] p2 = {1, 3, 2, 8, 4, 9};
        int fee = 2;
        System.out.println("手续费 " + fee + "，价格 " + Arrays.toString(p2)
                + "：朴素贪心 = " + naiveGreedyWithFee(p2, fee)
                + "，暴力 = " + bruteUnlimited(p2, 0, false, 0, fee));

        int[] coins = {1, 3, 4};
        List<Integer> used = new ArrayList<Integer>();
        int g = coinGreedy(coins, 6, used);
        System.out.println("面额 {1,3,4} 凑 6：贪心 " + g + " 枚 " + used + "，最优 " + coinOptimal(coins, 6) + " 枚");
        used.clear();
        int[] cny = {1, 2, 5, 10, 20, 50, 100};
        int g2 = coinGreedy(cny, 188, used);
        System.out.println("人民币面额凑 188：贪心 " + g2 + " 枚 " + used + "，最优 " + coinOptimal(cny, 188) + " 枚");
    }
}
