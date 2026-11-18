package labs.algo.monotonic_stack_queue;

import java.util.Arrays;

/**
 * 每一笔成交，往后第一笔“更高价”是第几笔：单调栈。
 * 栈里放“还没等到更高价”的成交下标，从栈底到栈顶价格不升。
 */
public class NextHigher {

    /** ans[i] = i 之后第一个严格比 prices[i] 高的下标；没有就是 -1。 */
    public static int[] nextHigher(long[] prices, boolean trace) {
        int n = prices.length;
        int[] ans = new int[n];
        Arrays.fill(ans, -1);
        int[] stack = new int[n];       // 用数组当栈，不用 java.util.Stack（它继承 Vector，每个方法都加锁）
        int top = 0;
        for (int i = 0; i < n; i++) {
            StringBuilder popped = new StringBuilder();
            while (top > 0 && prices[stack[top - 1]] < prices[i]) {   // 严格小于才弹：相等不算“更高”
                int j = stack[--top];
                ans[j] = i;                                         // j 等到了它的更高价
                popped.append(prices[j]).append(' ');
            }
            stack[top++] = i;
            if (trace) {
                StringBuilder s = new StringBuilder("[");
                for (int k = 0; k < top; k++) s.append(k == 0 ? "" : " ").append(prices[stack[k]]);
                System.out.printf("第%d笔 %d  弹出 %-12s 栈 %s]%n", i, prices[i],
                        popped.length() == 0 ? "-" : popped.toString().trim(), s);
            }
        }
        return ans;   // 最后还在栈里的，到收盘都没等到更高价，保持 -1
    }

    public static void main(String[] args) {
        long[] prices = {101, 103, 102, 99, 100, 98, 97, 104};
        int[] ans = nextHigher(prices, true);
        System.out.println();
        for (int i = 0; i < prices.length; i++) {
            System.out.printf("第%d笔 %d → %s%n", i, prices[i],
                    ans[i] < 0 ? "之后没有更高价" : "第" + ans[i] + "笔 " + prices[ans[i]] + "（等了 " + (ans[i] - i) + " 笔）");
        }
    }
}
