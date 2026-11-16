package labs.design.design_patterns_in_frameworks;

/** 老代码的样子：一种券一个分支。真的 calcPrice() 有 7 种、420 行，这里留 3 种。它不认识满减。 */
public final class LegacyCalc {
    private LegacyCalc() {}

    public static long pay(Cart cart, Offer offer) {
        long total = cart.total(), discount = 0;
        if (offer.couponType() == 1) {                    // 现金券
            discount = Math.min(total, offer.couponValue());
        } else if (offer.couponType() == 3) {             // 折扣券
            discount = total - (total * offer.couponValue() + 50) / 100;
        } else if (offer.couponType() == 7) {             // 去年双 11 的临时券，活动早结束了，代码还在
            discount = total / 100_00 * offer.couponValue();
        }
        return total - discount;
    }
}
