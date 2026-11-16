package labs.design.refactoring_tech_debt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 先钉住，再搬，再双跑，最后才加功能。
 * 运行：mvn -q -pl design/refactoring-tech-debt compile exec:java
 *       -Dexec.mainClass=labs.design.refactoring_tech_debt.RefactorWalkthrough
 */
public class RefactorWalkthrough {
    static final long[] TOTALS = {99_90, 1, 99, 1_00, 9_99, 99_99, 100_00, 123_45, 299_99, 300_00, 400_00, 1234_56};
    static final long[][] OFFERS = {{0, 0}, {1, 5_00}, {1, 10_00}, {1, 50_00}, {3, 95}, {3, 90}, {3, 85}, {3, 70}, {7, 5_00}};

    public static void main(String[] args) {
        System.out.println("== 1. 钉住：拿老代码的输出当标准答案（特征测试）");
        List<long[]> golden = new ArrayList<>();              // {total, type, value, 老代码的结果}
        for (long t : TOTALS) for (long[] o : OFFERS) golden.add(new long[]{t, o[0], o[1], Legacy.pay(t, (int) o[0], o[1])});
        System.out.println("记下 " + golden.size() + " 组输入和老代码的输出。不管这些输出“对不对”，现在线上就是这样");

        System.out.println("\n== 2. 搬的时候顺手把折扣券的舍入“修”了");
        report(golden, Moved.WITH_ROUNDING_FIX);

        System.out.println("\n== 3. 原样搬，连老毛病一起搬");
        report(golden, Moved.AS_IS);

        System.out.println("\n== 4. 双跑：一万笔模拟的线上订单，新旧两边都算，只用旧的结果");
        parallel("修了舍入的版本", Moved.WITH_ROUNDING_FIX, false);
        parallel("原样搬的版本", Moved.AS_IS, false);

        System.out.println("\n== 5. 加功能：打开满减的开关，再比一次");
        parallel("原样搬 + 满减开关打开", Moved.AS_IS, true);
    }

    static void report(List<long[]> golden, List<Rule> rules) {
        int diff = 0;
        StringBuilder first = new StringBuilder();
        for (long[] g : golden) {
            long now = Moved.pay(rules, g[0], (int) g[1], g[2]);
            if (now != g[3]) {
                if (diff < 3) first.append("  ").append(yuan(g[0])).append(" 元，").append(g[2] / 10.0).append(" 折：老代码 ").append(yuan(g[3])).append("，新代码 ").append(yuan(now)).append('\n');
                diff++;
            }
        }
        System.out.println(golden.size() + " 组里有 " + diff + " 组和老代码不一样" + (diff == 0 ? "，可以往下走" : "，前几组："));
        System.out.print(first);
    }

    static void parallel(String name, List<Rule> rules, boolean flagOn) {
        Random r = new Random(20261212);                       // 固定种子，每次跑出来一样
        int n = 10_000, diff = 0, diffWithPromotion = 0, promotionOrders = 0;
        long drift = 0;
        for (int i = 0; i < n; i++) {
            long total = 1_00 + r.nextInt(599_00);
            int type = new int[]{0, 1, 3, 7}[r.nextInt(4)];
            long value = type == 1 ? 10_00 : type == 3 ? new long[]{95, 90, 85, 70}[r.nextInt(4)] : type == 7 ? 5_00 : 0;
            boolean promotion = r.nextInt(10) < 3;
            boolean hit = promotion && total >= 300_00;
            if (hit) promotionOrders++;
            long oldPay = Legacy.pay(total, type, value);
            long newPay = Moved.payWithPromotion(rules, total, type, value, promotion, flagOn);
            if (oldPay != newPay) { diff++; drift += oldPay - newPay; if (hit) diffWithPromotion++; }
        }
        System.out.println(name + "：" + n + " 笔里 " + diff + " 笔不一样"
            + (diff == 0 ? "" : "，合计差 " + yuan(Math.abs(drift)) + " 元")
            + (flagOn ? "；其中 " + diffWithPromotion + " 笔是够门槛的满减订单（一共 " + promotionOrders + " 笔），别的订单差异 " + (diff - diffWithPromotion) + " 笔" : ""));
    }

    static String yuan(long cents) { return String.format("%d.%02d", cents / 100, cents % 100); }
}
