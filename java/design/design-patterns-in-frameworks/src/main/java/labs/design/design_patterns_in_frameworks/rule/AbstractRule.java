package labs.design.design_patterns_in_frameworks.rule;

import labs.design.design_patterns_in_frameworks.Cart;
import labs.design.design_patterns_in_frameworks.Offer;
import labs.design.design_patterns_in_frameworks.Quote;

/**
 * 模板方法：每条规则的骨架都一样（适用吗 → 减多少 → 记一笔），写死在父类的 final 方法里；
 * 子类只填“适用吗”和“减多少”两个空。
 */
public abstract class AbstractRule implements DiscountRule {
    @Override
    public final Quote apply(Quote quote, Cart cart, Offer offer) {
        if (!matches(cart, offer)) return quote;
        long amount = Math.min(quote.payable(), discount(quote, cart, offer));   // 不会减成负数
        return quote.minus(name(), amount);
    }

    protected abstract String name();
    protected abstract boolean matches(Cart cart, Offer offer);
    protected abstract long discount(Quote quote, Cart cart, Offer offer);
}
