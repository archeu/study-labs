package labs.design.ddd_bounded_context_aggregate.app;

import labs.design.ddd_bounded_context_aggregate.ordering.OrderEvents.OrderPaid;
import labs.design.ddd_bounded_context_aggregate.promotion.Coupon;
import labs.design.ddd_bounded_context_aggregate.promotion.Promotion;

import java.util.Map;

/** 营销这边听订单的事件：核销券、记预算。各在各的事务里做，所以要经得起重复投递。 */
public class PromotionEventHandler {
    private final Map<String, Promotion> promotions;
    private final Map<String, Coupon> coupons;

    public PromotionEventHandler(Map<String, Promotion> promotions, Map<String, Coupon> coupons) {
        this.promotions = promotions; this.coupons = coupons;
    }

    public String on(OrderPaid e) {
        StringBuilder sb = new StringBuilder();
        if (e.couponId() != null)
            sb.append(coupons.get(e.couponId()).redeem(e.orderId()) ? "券已核销" : "券之前核销过，跳过");
        if (e.promotionId() != null) {
            Promotion p = promotions.get(e.promotionId());
            boolean counted = p.consume(e.orderId(), e.promotionAmount());
            sb.append(sb.length() > 0 ? "；" : "").append(counted ? "预算记一笔" : "这张订单记过账，跳过");
        }
        return sb.toString();
    }
}
