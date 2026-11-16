package labs.design.refactoring_tech_debt;

import java.util.List;

/** 把 Legacy 的三个分支搬成三个规则对象。有两个版本，差别只在折扣券的一行。 */
public final class Moved {
    private Moved() {}

    static final Rule CASH = rule(1, (total, value) -> total - value);
    static final Rule PER_100 = rule(7, (total, value) -> total - total / 100_00 * value);
    /** 原样搬：连向下取整一起搬过来。 */
    static final Rule PERCENT_AS_IS = rule(3, (total, value) -> total * value / 100);
    /** 搬的时候看到“明显写错了”，顺手改成了四舍五入。 */
    static final Rule PERCENT_FIXED = rule(3, (total, value) -> (total * value + 50) / 100);

    public static final List<Rule> AS_IS = List.of(CASH, PERCENT_AS_IS, PER_100);
    public static final List<Rule> WITH_ROUNDING_FIX = List.of(CASH, PERCENT_FIXED, PER_100);

    public static long pay(List<Rule> rules, long total, int type, long value) {
        for (Rule r : rules) if (r.matches(type)) return Math.max(0, r.pay(total, value));
        return total;
    }

    /** 新功能：满 300 减 40，先满减后用券。开关关着的时候，结果必须和老代码一模一样。 */
    public static long payWithPromotion(List<Rule> rules, long total, int type, long value, boolean promotion, boolean flagOn) {
        long base = flagOn && promotion && total >= 300_00 ? total - 40_00 : total;
        return pay(rules, base, type, value);
    }

    interface Calc { long apply(long total, long value); }

    private static Rule rule(int type, Calc calc) {
        return new Rule() {
            @Override public boolean matches(int t) { return t == type; }
            @Override public long pay(long total, long value) { return calc.apply(total, value); }
        };
    }
}
