package labs.design.ddd_bounded_context_aggregate.ordering;

/**
 * 订单上下文 · 值对象：下单那一刻，每一行便宜了多少。这里的“优惠”是一个事实，存下来就不再变。
 * 只记营销那边的 id，不引用营销的对象。
 */
public record PriceSnapshot(long[] lineDiscounts, String promotionId, long promotionAmount, String couponId) {
    public PriceSnapshot {
        lineDiscounts = lineDiscounts.clone();          // 拷一份，外面改不到
    }
    @Override public long[] lineDiscounts() { return lineDiscounts.clone(); }
}
