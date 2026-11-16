package labs.design.requirements_modeling;

/**
 * “calcPrice 里加个 if，3 天”的写法：原来只有券，满减的 if 加在了券分支后面。
 * 没有人问过顺序、门槛按哪个金额判断、退一件怎么退。
 */
public class NaiveIfPricing implements Pricing {

    @Override
    public long pay(long[] cart, boolean promo, boolean coupon) {
        long pay = Pricing.total(cart);
        if (coupon) pay = Pricing.ninetyPercent(pay);       // 原来就有的券分支
        if (promo && pay >= THRESHOLD) pay -= MINUS;        // 新加的 if：加在了后面
        return pay;
    }

    @Override
    public long[] shares(long[] cart, boolean promo, boolean coupon) {
        return null;                                        // orders 表只有整单优惠一个数
    }

    @Override
    public long refund(long[] cart, boolean promo, boolean coupon, int line) {
        // 退款补偿里复制粘贴的老逻辑：只认识券，按券重算这一行
        return coupon ? Pricing.ninetyPercent(cart[line]) : cart[line];
    }
}
