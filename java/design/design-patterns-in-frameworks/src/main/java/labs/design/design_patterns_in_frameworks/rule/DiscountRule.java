package labs.design.design_patterns_in_frameworks.rule;

import labs.design.design_patterns_in_frameworks.Cart;
import labs.design.design_patterns_in_frameworks.Offer;
import labs.design.design_patterns_in_frameworks.Quote;

/** 策略：一种优惠一个实现。链按 order() 从小到大依次调用。 */
public interface DiscountRule {
    int order();
    Quote apply(Quote quote, Cart cart, Offer offer);
}
