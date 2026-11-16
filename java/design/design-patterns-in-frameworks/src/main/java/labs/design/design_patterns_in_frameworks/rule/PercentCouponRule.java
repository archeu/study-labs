package labs.design.design_patterns_in_frameworks.rule;

import labs.design.design_patterns_in_frameworks.Cart;
import labs.design.design_patterns_in_frameworks.Offer;
import labs.design.design_patterns_in_frameworks.Quote;

public class PercentCouponRule extends AbstractRule {
    @Override public int order() { return 20; }
    @Override protected String name() { return "折扣券"; }
    @Override protected boolean matches(Cart cart, Offer offer) { return offer.couponType() == 3; }
    @Override protected long discount(Quote quote, Cart cart, Offer offer) {
        return quote.payable() - (quote.payable() * offer.couponValue() + 50) / 100;   // 在“现在该付的”上打折
    }
}
