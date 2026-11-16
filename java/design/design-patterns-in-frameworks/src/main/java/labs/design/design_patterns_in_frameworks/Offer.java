package labs.design.design_patterns_in_frameworks;

/**
 * 这一单能用的优惠。couponType 沿用老代码的编号：0 没有券，1 现金券（couponValue = 减多少分），
 * 3 折扣券（couponValue = 90 表示 9 折），7 去年双 11 的临时券“每满 100 减 couponValue 分”。
 */
public record Offer(boolean promotion, int couponType, long couponValue) {
    public static final Offer NONE = new Offer(false, 0, 0);
}
