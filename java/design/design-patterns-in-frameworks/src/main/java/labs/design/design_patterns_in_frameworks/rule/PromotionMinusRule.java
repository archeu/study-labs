package labs.design.design_patterns_in_frameworks.rule;

import labs.design.design_patterns_in_frameworks.Cart;
import labs.design.design_patterns_in_frameworks.Offer;
import labs.design.design_patterns_in_frameworks.Quote;

/** 新需求：满 300 减 40。一个新类，别的类一行不动。order 就是规则表的 R1：10 排在券（20）前面。 */
public class PromotionMinusRule extends AbstractRule {
    private final int order;
    public PromotionMinusRule() { this(10); }
    public PromotionMinusRule(int order) { this.order = order; }

    @Override public int order() { return order; }
    @Override protected String name() { return "满减"; }
    @Override protected boolean matches(Cart cart, Offer offer) { return offer.promotion() && cart.total() >= 300_00; }   // 门槛看原价
    @Override protected long discount(Quote quote, Cart cart, Offer offer) { return 40_00; }
}
