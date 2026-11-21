package labs.algo.problem_map;

import java.util.PriorityQueue;

/**
 * 全景第 5 站换成面试题：LeetCode 1801「积压订单中的订单总数」。
 * orders[i] = [价格, 数量, 类型]，类型 0 是买、1 是卖。买单和卖簿里最便宜的比，卖单和买簿里最贵的比，
 * 能成交就成交，剩下的挂进簿子。最后问簿子里还剩多少手（对 1e9+7 取模）。
 * 认出题型：要反复取“最便宜的卖 / 最贵的买” → 两个堆（第 6 课）。
 */
public class Backlog {
    static int solve(int[][] orders) {
        PriorityQueue<int[]> buy = new PriorityQueue<int[]>((x, y) -> Integer.compare(y[0], x[0]));  // 买簿：价高的在顶
        PriorityQueue<int[]> sell = new PriorityQueue<int[]>((x, y) -> Integer.compare(x[0], y[0])); // 卖簿：价低的在顶
        for (int[] o : orders) {
            int price = o[0], amount = o[1];
            if (o[2] == 0) {
                while (amount > 0 && !sell.isEmpty() && sell.peek()[0] <= price) {
                    int[] top = sell.peek();
                    int d = Math.min(amount, top[1]);
                    amount -= d; top[1] -= d;
                    if (top[1] == 0) sell.poll();
                }
                if (amount > 0) buy.offer(new int[]{price, amount});
            } else {
                while (amount > 0 && !buy.isEmpty() && buy.peek()[0] >= price) {
                    int[] top = buy.peek();
                    int d = Math.min(amount, top[1]);
                    amount -= d; top[1] -= d;
                    if (top[1] == 0) buy.poll();
                }
                if (amount > 0) sell.offer(new int[]{price, amount});
            }
        }
        long total = 0;
        for (int[] x : buy) total += x[1];
        for (int[] x : sell) total += x[1];
        return (int) (total % 1_000_000_007L);
    }

    public static void main(String[] args) {
        System.out.println("示例 1：" + solve(new int[][]{{10, 5, 0}, {15, 2, 1}, {25, 1, 1}, {30, 4, 0}}));
        System.out.println("示例 2：" + solve(new int[][]{{7, 1000000000, 1}, {15, 3, 0}, {5, 999999995, 0}, {5, 1, 1}}));
        // 全景的那一单：卖簿 100.50 × 1、102.00 × 3，用户 1024 买 2 手限价 101.00（价格用分）
        System.out.println("全景第 5 站：" + solve(new int[][]{{10050, 1, 1}, {10200, 3, 1}, {10100, 2, 0}}) + " 手还挂着（卖簿 3 手 + 买簿剩下的 1 手）");
    }
}
