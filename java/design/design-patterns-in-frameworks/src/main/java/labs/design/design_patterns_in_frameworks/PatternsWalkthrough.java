package labs.design.design_patterns_in_frameworks;

import labs.design.design_patterns_in_frameworks.rule.CashCouponRule;
import labs.design.design_patterns_in_frameworks.rule.DiscountRule;
import labs.design.design_patterns_in_frameworks.rule.Per100CouponRule;
import labs.design.design_patterns_in_frameworks.rule.PercentCouponRule;
import labs.design.design_patterns_in_frameworks.rule.PromotionMinusRule;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 if/else 的算价拆成策略 + 模板方法 + 责任链，再套一层代理。
 * 运行：mvn -q -pl design/design-patterns-in-frameworks compile exec:java
 *       -Dexec.mainClass=labs.design.design_patterns_in_frameworks.PatternsWalkthrough
 */
public class PatternsWalkthrough {
    static final Cart ORDER_1024 = new Cart(199_00, 129_00, 72_00);

    public static void main(String[] args) {
        List<DiscountRule> oldRules = List.of(new CashCouponRule(), new PercentCouponRule(), new Per100CouponRule());

        System.out.println("== 1. 先搬：三种券从 if/else 搬成三个类，行为一点不许变");
        Cart[] carts = {new Cart(5_00), new Cart(99_99), new Cart(100_00), new Cart(299_99), ORDER_1024, new Cart(1234_56, 78_90)};
        Offer[] offers = {Offer.NONE, new Offer(false, 1, 10_00), new Offer(false, 3, 90), new Offer(false, 3, 85), new Offer(false, 7, 5_00)};
        Pricing moved = new PricingChain(oldRules);
        int same = 0, all = 0;
        for (Cart c : carts) for (Offer o : offers) { all++; if (LegacyCalc.pay(c, o) == moved.quote(c, o).payable()) same++; }
        System.out.println("老代码和新链逐个比：" + same + " / " + all + " 一致");

        System.out.println("\n== 2. 后改：加满减，就是多一个类");
        List<DiscountRule> withPromotion = new ArrayList<>(oldRules);
        withPromotion.add(new PromotionMinusRule());
        Offer both = new Offer(true, 3, 90);
        Quote q = new PricingChain(withPromotion).quote(ORDER_1024, both);
        System.out.println("用户 1024 那单：" + String.join(" → ", q.trace()) + "，应付 " + yuan(q.payable()));
        System.out.println("改了几个已有的类：0；新增：PromotionMinusRule");
        System.out.println("老代码算同一单：" + yuan(LegacyCalc.pay(ORDER_1024, both)) + "（它不认识满减）");

        System.out.println("\n== 3. 链的顺序本身是业务规则");
        List<DiscountRule> wrongOrder = new ArrayList<>(oldRules);
        wrongOrder.add(new PromotionMinusRule(30));          // 排到券后面
        Quote w = new PricingChain(wrongOrder).quote(ORDER_1024, both);
        System.out.println("把满减的 order 从 10 改成 30：" + String.join(" → ", w.trace()) + "，应付 " + yuan(w.payable()));

        System.out.println("\n== 4. 代理：不动算价的代码，在它前后加东西");
        Pricing proxied = TraceProxy.wrap(new PricingChain(withPromotion));
        System.out.println("  拿到的对象是 " + proxied.getClass().getSimpleName() + "，不是 PricingChain，但它也是 Pricing");
        proxied.quote(ORDER_1024, both);
    }

    public static String yuan(long cents) { return String.format("%d.%02d", cents / 100, cents % 100); }
}
