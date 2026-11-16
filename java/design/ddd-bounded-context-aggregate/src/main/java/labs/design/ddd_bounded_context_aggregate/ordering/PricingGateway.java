package labs.design.ddd_bounded_context_aggregate.ordering;

/** 订单上下文向外要报价的口子。接口属于订单，用订单自己的词；谁来实现，订单不关心。 */
public interface PricingGateway {
    PriceSnapshot priceFor(String orderId, long[] prices);
}
