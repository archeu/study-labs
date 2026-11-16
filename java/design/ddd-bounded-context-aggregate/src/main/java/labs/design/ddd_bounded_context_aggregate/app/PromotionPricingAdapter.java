package labs.design.ddd_bounded_context_aggregate.app;

import labs.design.ddd_bounded_context_aggregate.ordering.PriceSnapshot;
import labs.design.ddd_bounded_context_aggregate.ordering.PricingGateway;
import labs.design.ddd_bounded_context_aggregate.promotion.Coupon;
import labs.design.ddd_bounded_context_aggregate.promotion.PricingService;
import labs.design.ddd_bounded_context_aggregate.promotion.Promotion;
import labs.design.ddd_bounded_context_aggregate.promotion.Quote;

/**
 * 防腐层：整个工程里只有这一个类同时认识两边。
 * 它调营销算价、锁券，再把营销的 Quote 翻译成订单的 PriceSnapshot。
 * 真实系统里这里是一次 HTTP 调用，营销改了返回格式，只改这一个类。
 */
public class PromotionPricingAdapter implements PricingGateway {
    private final PricingService pricing = new PricingService();
    private final Promotion promotion;
    private final Coupon coupon;

    public PromotionPricingAdapter(Promotion promotion, Coupon coupon) { this.promotion = promotion; this.coupon = coupon; }

    @Override
    public PriceSnapshot priceFor(String orderId, long[] prices) {
        Quote q = pricing.quote(prices, promotion, coupon);
        if (coupon != null) coupon.lock(orderId);
        return new PriceSnapshot(q.lineDiscounts(), q.promotionId(), q.promotionAmount(), q.couponId());
    }
}
