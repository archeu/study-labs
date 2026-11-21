package labs.algo.dp_knapsack;

/**
 * 序列 DP：编辑距离。网关收到交易对代码 BTC-UDST，不认识；在已上线的交易对里找“改动最少”的那个，提示“你是不是要 BTC-USDT”。
 * dp[i][j] = a 的前 i 个字符变成 b 的前 j 个字符，最少要几步（插入、删除、替换各算 1 步）。
 */
public class EditDistance {

    static int dist(String a, String b, boolean print) {
        int n = a.length(), m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;   // 删光
        for (int j = 0; j <= m; j++) dp[0][j] = j;   // 从空插入
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++) {
                int same = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j - 1] + same,                 // 替换（或相同不动）
                           Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));     // 删 / 插
            }
        if (print) {
            StringBuilder sb = new StringBuilder("      ");
            for (int j = 0; j < m; j++) sb.append("  ").append(b.charAt(j));
            System.out.println(sb);
            for (int i = 0; i <= n; i++) {
                sb = new StringBuilder(i == 0 ? "   " : "  " + a.charAt(i - 1));
                for (int j = 0; j <= m; j++) sb.append(String.format("%3d", dp[i][j]));
                System.out.println(sb);
            }
        }
        return dp[n][m];
    }

    public static void main(String[] args) {
        String typed = "BTC-UDST";
        String[] listed = {"BTC-USDT", "BTC-USDC", "ETH-USDT", "BCH-USDT", "BTC-EUR", "DOT-USDT"};
        dist("UDST", "USDT", true);
        String best = null; int bestD = Integer.MAX_VALUE;
        for (String s : listed) {
            int d = dist(typed, s, false);
            System.out.println(typed + " → " + s + "：" + d);
            if (d < bestD) { bestD = d; best = s; }
        }
        System.out.println("你是不是要：" + best);

        // 两份 1 万字符的流水做比对：O(n*m) = 1 亿格
        StringBuilder x = new StringBuilder(), y = new StringBuilder();
        java.util.Random r = new java.util.Random(22);
        for (int i = 0; i < 10000; i++) { char c = (char) ('A' + r.nextInt(4)); x.append(c); if (r.nextInt(50) != 0) y.append(c); else y.append('Z'); }
        long t0 = System.nanoTime();
        int d = dist(x.toString(), y.toString(), false);
        System.out.println("1 万 × 1 万字符：编辑距离 " + d + "，" + (System.nanoTime() - t0) / 1000000 + " ms，二维表约 "
                + (10001L * 10001 * 4 / 1024 / 1024) + " MB");
    }
}
