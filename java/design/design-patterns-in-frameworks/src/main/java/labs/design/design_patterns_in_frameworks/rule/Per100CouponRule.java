package labs.design.design_patterns_in_frameworks.rule;

import labs.design.design_patterns_in_frameworks.Cart;
import labs.design.design_patterns_in_frameworks.Offer;
import labs.design.design_patterns_in_frameworks.Quote;

/** 去年双 11 的临时券：每满 100 减 N。活动结束了，想下线它，删掉这一个类就行。 */
public class Per100CouponRule extends AbstractRule {
    @Override public int order() { return 20; }
    @Override protected String name() { return "每满 100 减"; }
    @Override protected boolean matches(Cart cart, Offer offer) { return offer.couponType() == 7; }
    @Override protected long discount(Quote quote, Cart cart, Offer offer) { return cart.total() / 100_00 * offer.couponValue(); }
}
