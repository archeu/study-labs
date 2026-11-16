package labs.design.requirements_modeling;

/** 一次算价要回答的三件事。金额一律用“分”，避免浮点误差。 */
public interface Pricing {

    /** 应付金额。promo：这一单是否参加“满 300 减 40”；coupon：是否用了一张 9 折券。 */
    long pay(long[] cart, boolean promo, boolean coupon);

    /** 每一行分摊了多少优惠；说不出来就返回 null。 */
    long[] shares(long[] cart, boolean promo, boolean coupon);

    /** 退掉第 line 行，退多少钱。 */
    long refund(long[] cart, boolean promo, boolean coupon, int line);

    long THRESHOLD = 300_00, MINUS = 40_00;

    static long total(long[] cart) {
        long t = 0;
        for (long c : cart) t += c;
        return t;
    }

    /** 打 9 折，四舍五入到分。 */
    static long ninetyPercent(long amount) {
        return (amount * 90 + 50) / 100;
    }
}
