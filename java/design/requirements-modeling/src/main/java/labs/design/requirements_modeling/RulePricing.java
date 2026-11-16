package labs.design.requirements_modeling;

/**
 * 按规则表写的版本。
 * R1 先满减后用券，门槛按原价判断；R3 优惠按行金额比例分摊到分，尾差归金额最大的一行；
 * R4 退一件，退这一行的实付（行原价 - 行分摊）。
 */
public class RulePricing implements Pricing {

    @Override
    public long pay(long[] cart, boolean promo, boolean coupon) {
        long pay = Pricing.total(cart);
        if (promo && pay >= THRESHOLD) pay -= MINUS;        // R1：先满减，门槛看原价
        if (coupon) pay = Pricing.ninetyPercent(pay);       // R1：再用券
        return pay;
    }

    @Override
    public long[] shares(long[] cart, boolean promo, boolean coupon) {
        long total = Pricing.total(cart);
        long discount = total - pay(cart, promo, coupon);
        long[] shares = new long[cart.length];
        long given = 0;
        int biggest = 0;
        for (int i = 0; i < cart.length; i++) {
            shares[i] = (discount * cart[i] * 2 + total) / (total * 2);   // 四舍五入到分
            given += shares[i];
            if (cart[i] > cart[biggest]) biggest = i;
        }
        shares[biggest] += discount - given;                // R3：尾差归金额最大的一行
        return shares;
    }

    @Override
    public long refund(long[] cart, boolean promo, boolean coupon, int line) {
        return cart[line] - shares(cart, promo, coupon)[line];   // R4：退这一行的实付
    }
}
