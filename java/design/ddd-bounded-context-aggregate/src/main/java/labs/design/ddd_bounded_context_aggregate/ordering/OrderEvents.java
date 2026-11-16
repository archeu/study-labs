package labs.design.ddd_bounded_context_aggregate.ordering;

/** 领域事件：过去时，带上发生时的事实，不带“你去做什么”。 */
public final class OrderEvents {
    private OrderEvents() {}
    public record OrderPaid(String orderId, long paid, String promotionId, long promotionAmount, String couponId) {}
    public record OrderLineRefunded(String orderId, int line, long refunded) {}
}
