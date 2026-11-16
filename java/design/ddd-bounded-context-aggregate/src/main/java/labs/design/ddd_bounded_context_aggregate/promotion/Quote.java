package labs.design.ddd_bounded_context_aggregate.promotion;

/** 营销给出的一次报价：营销自己的说法，订单不直接用它。 */
public record Quote(long payable, long[] lineDiscounts, String promotionId, long promotionAmount, String couponId) {}
