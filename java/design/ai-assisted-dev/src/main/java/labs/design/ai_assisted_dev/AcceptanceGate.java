package labs.design.ai_assisted_dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四份“看起来都对”的满减实现，每份都带着自己写的测试，自测全绿。
 * 拿三种不是它自己出的题去验：三方签字的算例、老代码的特征测试、退款入口的算例。
 * 这四份是手写的，模拟 AI 助手常见的四种错法，不是某个模型的真实输出。
 * 运行：mvn -q -pl design/ai-assisted-dev compile exec:java -Dexec.mainClass=labs.design.ai_assisted_dev.AcceptanceGate
 */
public class AcceptanceGate {

    /** 一份实现要回答两件事：下单入口算应付；退款入口算退一行退多少。pct = 90 表示 9 折，100 表示没券。 */
    interface Impl {
        long pay(long[] cart, boolean promo, int pct);
        long refundLine(long[] cart, boolean promo, int pct, int line);
    }

    static long total(long[] cart) { long t = 0; for (long c : cart) t += c; return t; }

    /** 老代码：不认识满减；折扣向下取整。 */
    static long legacyPay(long total, int pct) { return total * pct / 100; }

    /** 按比例分摊，尾差归最大行，然后退这一行的实付。 */
    static long refundByShare(long[] cart, long paid, int line) {
        long t = total(cart), discount = t - paid, given = 0;
        long[] s = new long[cart.length];
        int big = 0;
        for (int i = 0; i < cart.length; i++) { s[i] = (discount * cart[i] * 2 + t) / (t * 2); given += s[i]; if (cart[i] > cart[big]) big = i; }
        s[big] += discount - given;
        return cart[line] - s[line];
    }

    /** 对的那份：先满减（门槛看原价），后用券；舍入照老代码；退款按分摊。 */
    static final Impl CORRECT = new Impl() {
        public long pay(long[] cart, boolean promo, int pct) { long t = total(cart); return (promo && t >= 300_00 ? t - 40_00 : t) * pct / 100; }
        public long refundLine(long[] cart, boolean promo, int pct, int line) { return refundByShare(cart, pay(cart, promo, pct), line); }
    };
    /** A：满减加在了券后面（任务说明里没写顺序）。 */
    static final Impl A_ORDER = new Impl() {
        public long pay(long[] cart, boolean promo, int pct) { long t = total(cart), p = t * pct / 100; return promo && t >= 300_00 ? p - 40_00 : p; }
        public long refundLine(long[] cart, boolean promo, int pct, int line) { return refundByShare(cart, pay(cart, promo, pct), line); }
    };
    /** B：顺序对了，门槛却拿打完折的金额去比。 */
    static final Impl B_THRESHOLD = new Impl() {
        public long pay(long[] cart, boolean promo, int pct) { long t = total(cart); return promo && t * pct / 100 >= 300_00 ? (t - 40_00) * pct / 100 : t * pct / 100; }
        public long refundLine(long[] cart, boolean promo, int pct, int line) { return refundByShare(cart, pay(cart, promo, pct), line); }
    };
    /** C：逻辑全对，顺手把“明显写错的”向下取整改成了四舍五入。 */
    static final Impl C_ROUNDING = new Impl() {
        public long pay(long[] cart, boolean promo, int pct) { long t = total(cart); return ((promo && t >= 300_00 ? t - 40_00 : t) * pct + 50) / 100; }
        public long refundLine(long[] cart, boolean promo, int pct, int line) { return refundByShare(cart, pay(cart, promo, pct), line); }
    };
    /** D：下单入口改对了；退款补偿那个入口没人告诉它，还是老逻辑：按券重算这一行。 */
    static final Impl D_ENTRY = new Impl() {
        public long pay(long[] cart, boolean promo, int pct) { return CORRECT.pay(cart, promo, pct); }
        public long refundLine(long[] cart, boolean promo, int pct, int line) { return cart[line] * pct / 100; }
    };

    static final long[] ORDER_1024 = {199_00, 129_00, 72_00};

    public static void main(String[] args) {
        Map<String, Impl> candidates = new LinkedHashMap<>();
        candidates.put("A 满减加在券后面", A_ORDER);
        candidates.put("B 门槛看折后金额", B_THRESHOLD);
        candidates.put("C 顺手修了舍入", C_ROUNDING);
        candidates.put("D 漏了退款入口", D_ENTRY);
        candidates.put("对的那份", CORRECT);

        System.out.printf("%-14s | %-10s | %-12s | %-12s | %-12s%n", "实现", "它自己的测试", "签字的算例", "特征测试", "退款入口的算例");
        for (Map.Entry<String, Impl> e : candidates.entrySet()) {
            Impl c = e.getValue();
            System.out.printf("%-14s | %-12s | %-14s | %-14s | %-12s%n", e.getKey(), selfTests(c), examples(c), characterization(c), refundEntry(c));
        }
    }

    /** 它自己写的 12 个测试：期望值是它按自己的理解算出来的，所以一定全过。 */
    static String selfTests(Impl c) {
        long[][] carts = {ORDER_1024, {200_00, 100_00}, {199_00, 72_00}, {99_90}};
        int pass = 0, n = 0;
        for (long[] cart : carts) for (int pct : new int[]{100, 90, 85}) {
            long expectedByItsOwnUnderstanding = c.pay(cart, true, pct);
            n++; if (c.pay(cart, true, pct) == expectedByItsOwnUnderstanding) pass++;
        }
        return pass + "/" + n + " ✓";
    }

    /** 三方签字的算例里走下单入口的 5 个（E1～E5）。 */
    static String examples(Impl c) {
        List<String> failed = new ArrayList<>();
        if (c.pay(ORDER_1024, false, 90) != 360_00) failed.add("E1");
        if (c.pay(ORDER_1024, true, 100) != 360_00) failed.add("E2");
        if (c.pay(ORDER_1024, true, 90) != 324_00) failed.add("E3");
        if (c.pay(new long[]{199_00, 72_00}, true, 90) != 243_90) failed.add("E4");
        if (c.pay(new long[]{200_00, 100_00}, true, 90) != 234_00) failed.add("E5");
        return failed.isEmpty() ? "5/5 ✓" : "✗ " + String.join(" ", failed);
    }

    /** 老代码的特征测试：不参加满减时，必须和老代码一分不差。 */
    static String characterization(Impl c) {
        long[] totals = {99_90, 1, 99, 1_00, 9_99, 99_99, 100_00, 123_45, 299_99, 300_00, 400_00, 1234_56};
        int diff = 0, n = 0;
        for (long t : totals) for (int pct : new int[]{100, 95, 90, 85, 70}) { n++; if (c.pay(new long[]{t}, false, pct) != legacyPay(t, pct)) diff++; }
        return diff == 0 ? n + "/" + n + " ✓" : "✗ " + diff + " 组不一样";
    }

    /** E6 走退款补偿那个入口：用户 1024 那单退保温杯，应退 104.49。 */
    static String refundEntry(Impl c) {
        long r = c.refundLine(ORDER_1024, true, 90, 1);
        return r == 104_49 ? "104.49 ✓" : "✗ 退了 " + r / 100 + "." + String.format("%02d", r % 100);
    }
}
