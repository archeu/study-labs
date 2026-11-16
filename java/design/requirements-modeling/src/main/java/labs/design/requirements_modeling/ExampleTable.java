package labs.design.requirements_modeling;

import java.util.Arrays;

/**
 * 把三方签字的算例写成一张能跑的表，拿两种实现各跑一遍。
 * 运行：mvn -q -pl design/requirements-modeling compile exec:java
 *       -Dexec.mainClass=labs.design.requirements_modeling.ExampleTable
 */
public class ExampleTable {

    static final long[] ORDER_1024 = {199_00, 129_00, 72_00};   // A 耳机、B 保温杯、C 数据线

    /** 一个算例：名字、对应哪条规则、购物车、条件、问什么、期望答案。 */
    static final class Example {
        final String id, name, rule, ask;
        final long[] cart;
        final boolean promo, coupon;
        final String expected;

        Example(String id, String name, String rule, long[] cart, boolean promo, boolean coupon, String ask, String expected) {
            this.id = id; this.name = name; this.rule = rule; this.cart = cart;
            this.promo = promo; this.coupon = coupon; this.ask = ask; this.expected = expected;
        }

        String answer(Pricing p) {
            switch (ask) {
                case "应付": return yuan(p.pay(cart, promo, coupon));
                case "退 B": return yuan(p.refund(cart, promo, coupon, 1));
                default:
                    long[] s = p.shares(cart, promo, coupon);
                    return s == null ? "答不出" : String.join(" / ", Arrays.stream(s).mapToObj(ExampleTable::yuan).toArray(String[]::new));
            }
        }
    }

    static final Example[] EXAMPLES = {
        new Example("E1", "只用 9 折券", "已有", ORDER_1024, false, true, "应付", "360.00"),
        new Example("E2", "只有满减", "活动", ORDER_1024, true, false, "应付", "360.00"),
        new Example("E3", "满减 + 券（用户 1024 那单）", "R1", ORDER_1024, true, true, "应付", "324.00"),
        new Example("E4", "271 元，不满门槛 + 券", "R1", new long[]{199_00, 72_00}, true, true, "应付", "243.90"),
        new Example("E5", "正好 300 元 + 券", "R1", new long[]{200_00, 100_00}, true, true, "应付", "234.00"),
        new Example("E6", "E3 那单退保温杯", "R3 R4", ORDER_1024, true, true, "退 B", "104.49"),
        new Example("E7", "三行都是 133.33，尾差 1 分", "R3", new long[]{133_33, 133_33, 133_33}, true, true, "分摊", "25.34 / 25.33 / 25.33"),
    };

    public static void main(String[] args) {
        Pricing naive = new NaiveIfPricing(), rule = new RulePricing();
        int okNaive = 0, okRule = 0;
        System.out.printf("%-4s %-8s %-6s %-22s | %-24s | %s%n", "算例", "规则", "问", "期望", "加个 if", "按规则表");
        for (Example e : EXAMPLES) {
            String a = e.answer(naive), b = e.answer(rule);
            boolean pa = a.equals(e.expected), pb = b.equals(e.expected);
            if (pa) okNaive++;
            if (pb) okRule++;
            System.out.printf("%-4s %-8s %-6s %-22s | %-22s %s | %-22s %s   %s%n",
                e.id, e.rule, e.ask, e.expected, a, pa ? "✓" : "✗", b, pb ? "✓" : "✗", e.name);
        }
        System.out.printf("%n加个 if：%d / %d 通过；按规则表：%d / %d 通过%n", okNaive, EXAMPLES.length, okRule, EXAMPLES.length);
    }

    static String yuan(long cents) {
        return String.format("%d.%02d", cents / 100, cents % 100);
    }
}
