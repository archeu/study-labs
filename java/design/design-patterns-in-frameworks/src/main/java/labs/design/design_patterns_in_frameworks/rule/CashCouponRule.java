package labs.design.design_patterns_in_frameworks.rule;

import labs.design.design_patterns_in_frameworks.Cart;
import labs.design.design_patterns_in_frameworks.Offer;
import labs.design.design_patterns_in_frameworks.Quote;

public class CashCouponRule extends AbstractRule {
    @Override public int order() { return 20; }
    @Override protected String name() { return "现金券"; }
    @Override protected boolean matches(Cart cart, Offer offer) { return offer.couponType() == 1; }
    @Override protected long discount(Quote quote, Cart cart, Offer offer) { return offer.couponValue(); }
}
