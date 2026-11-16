package labs.design.design_patterns_in_frameworks;

import labs.design.design_patterns_in_frameworks.rule.DiscountRule;

import java.util.Comparator;
import java.util.List;

/**
 * 责任链：把报价按顺序递给每一条规则，每条规则只改自己那一部分。
 * 在 Spring 里，构造器参数写 List<DiscountRule> 就能拿到全部实现；顺序在这里显式排一遍，不靠注入的顺序。
 */
public class PricingChain implements Pricing {
    private final List<DiscountRule> rules;

    public PricingChain(List<DiscountRule> rules) {
        this.rules = rules.stream().sorted(Comparator.comparingInt(DiscountRule::order)).toList();
    }

    @Override
    public Quote quote(Cart cart, Offer offer) {
        Quote quote = Quote.of(cart.total());
        for (DiscountRule rule : rules) quote = rule.apply(quote, cart, offer);
        return quote;
    }
}
