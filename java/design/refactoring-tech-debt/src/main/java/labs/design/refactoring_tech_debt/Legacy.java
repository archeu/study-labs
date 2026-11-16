package labs.design.refactoring_tech_debt;

/**
 * 老代码，当成黑盒。金额用“分”。type：0 没券，1 现金券（value = 减多少分），
 * 3 折扣券（value = 90 表示 9 折），7 去年的临时券（每满 100 元减 value 分）。
 * 里面有一个多年的老毛病：折扣券向下取整，不是四舍五入。财务的对账一直是按它算的。
 */
public final class Legacy {
    private Legacy() {}

    public static long pay(long total, int type, long value) {
        long pay = total;
        if (type == 1) {
            pay = total - value;
        } else if (type == 3) {
            pay = total * value / 100;                 // 向下取整：9990 × 85% = 8491.5 → 8491
        } else if (type == 7) {
            pay = total - total / 100_00 * value;
        }
        return Math.max(0, pay);
    }
}
