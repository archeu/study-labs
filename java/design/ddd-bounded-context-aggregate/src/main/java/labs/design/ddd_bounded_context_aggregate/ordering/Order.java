package labs.design.ddd_bounded_context_aggregate.ordering;

import labs.design.ddd_bounded_context_aggregate.ordering.OrderEvents.OrderLineRefunded;
import labs.design.ddd_bounded_context_aggregate.ordering.OrderEvents.OrderPaid;

/**
 * 订单上下文 · 聚合根。外面只能通过它的方法改订单，不变量都在这里守：
 * 1) 应付 = 各行原价之和 - 各行分摊之和，且每行分摊不超过这一行的原价；
 * 2) 付了款才能退；3) 一行只能退一次；4) 退一行，退的是这一行的实付。
 */
public class Order {
    public enum Status { PLACED, PAID }

    private final String id;
    private final long[] prices;
    private final PriceSnapshot snapshot;
    private final long payable;
    private final boolean[] refunded;
    private Status status = Status.PLACED;

    private Order(String id, long[] prices, PriceSnapshot snapshot) {
        long[] d = snapshot.lineDiscounts();
        if (d.length != prices.length) throw new IllegalArgumentException("快照的行数和订单对不上");
        long total = 0, discount = 0;
        for (int i = 0; i < prices.length; i++) {
            if (d[i] < 0 || d[i] > prices[i]) throw new IllegalArgumentException("第 " + i + " 行的分摊 " + d[i] + " 超出了行原价");
            total += prices[i]; discount += d[i];
        }
        this.id = id; this.prices = prices.clone(); this.snapshot = snapshot;
        this.payable = total - discount; this.refunded = new boolean[prices.length];
    }

    public static Order place(String id, long[] prices, PricingGateway pricing) {
        return new Order(id, prices, pricing.priceFor(id, prices));
    }

    /** 测试用：直接给一份快照，看聚合拦不拦得住坏数据。 */
    public static Order placeWith(String id, long[] prices, PriceSnapshot snapshot) {
        return new Order(id, prices, snapshot);
    }

    public OrderPaid pay() {
        if (status != Status.PLACED) throw new IllegalStateException("订单 " + id + " 已经付过款");
        status = Status.PAID;
        return new OrderPaid(id, payable, snapshot.promotionId(), snapshot.promotionAmount(), snapshot.couponId());
    }

    public OrderLineRefunded refundLine(int line) {
        if (status != Status.PAID) throw new IllegalStateException("订单 " + id + " 还没付款，不能退");
        if (refunded[line]) throw new IllegalStateException("订单 " + id + " 的第 " + line + " 行已经退过");
        refunded[line] = true;
        return new OrderLineRefunded(id, line, prices[line] - snapshot.lineDiscounts()[line]);
    }

    public String id() { return id; }
    public long payable() { return payable; }
    public PriceSnapshot snapshot() { return snapshot; }
}
