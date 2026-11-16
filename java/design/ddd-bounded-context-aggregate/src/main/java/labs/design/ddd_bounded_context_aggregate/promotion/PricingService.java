package labs.design.ddd_bounded_context_aggregate.promotion;

/** 营销上下文 · 领域服务：算价。它不属于哪一个聚合，所以单独成一个服务。 */
public class PricingService {

    /** R1 先满减后用券，门槛看原价；R3 按行金额比例分摊，尾差归金额最大的一行。 */
    public Quote quote(long[] prices, Promotion promotion, Coupon coupon) {
        long total = 0;
        for (long p : prices) total += p;
        long minus = promotion == null ? 0 : promotion.minusFor(total);
        long payable = total - minus;
        if (coupon != null) payable = coupon.apply(payable);

        long discount = total - payable, given = 0;
        long[] shares = new long[prices.length];
        int biggest = 0;
        for (int i = 0; i < prices.length; i++) {
            shares[i] = (discount * prices[i] * 2 + total) / (total * 2);
            given += shares[i];
            if (prices[i] > prices[biggest]) biggest = i;
        }
        shares[biggest] += discount - given;
        return new Quote(payable, shares, minus > 0 ? promotion.id() : null, minus, coupon == null ? null : coupon.id());
    }
}
