package labs.jvm.jvm_troubleshooting;

/** 把一张优惠券按金额比例分摊到每件商品上（退款时要按件退）。金额单位：分。 */
public class Coupon {

    /** 有 bug 的写法：按比例分，分不完就再分一轮。比例一取整成 0，就永远分不完。 */
    static long[] split(long couponCents, long[] prices) {
        long total = 0;
        for (long p : prices) total += p;
        long[] share = new long[prices.length];
        long remaining = couponCents;
        while (remaining > 0) {
            long round = remaining;
            for (int i = 0; i < prices.length; i++) {
                long part = round * prices[i] / total;   // 1 分 × 29900 / 59800 = 0
                share[i] += part;
                remaining -= part;
            }
        }
        return share;
    }

    /** 改好的写法：前面按比例取整，尾差全给最后一件。一定只走一轮。 */
    static long[] splitFixed(long couponCents, long[] prices) {
        long total = 0;
        for (long p : prices) total += p;
        long[] share = new long[prices.length];
        long given = 0;
        for (int i = 0; i < prices.length - 1; i++) {
            share[i] = couponCents * prices[i] / total;
            given += share[i];
        }
        share[prices.length - 1] = couponCents - given;
        return share;
    }
}
