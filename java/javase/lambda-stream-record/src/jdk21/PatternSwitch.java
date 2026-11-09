import java.math.BigDecimal;

/**
 * 模式匹配的 switch 和 record 模式要 JDK 21（17 上是预览）。单独编译：
 *   javac --release 21 -d /tmp/ps src/jdk21/PatternSwitch.java && java -cp /tmp/ps PatternSwitch
 *   javap -c -p -cp /tmp/ps PatternSwitch | grep -A2 typeSwitch
 */
public class PatternSwitch {
    sealed interface Discount permits None, Minus, Percent {}
    record None() implements Discount {}
    record Minus(BigDecimal off) implements Discount {}
    record Percent(int pct) implements Discount {}

    static BigDecimal apply(Discount d, BigDecimal sum) {
        return switch (d) {                                   // 没有 default：三种都写到了，编译器才放行
            case None n -> sum;
            case Minus(BigDecimal off) when off.compareTo(sum) > 0 -> BigDecimal.ZERO;   // 守卫条件
            case Minus(BigDecimal off) -> sum.subtract(off);
            case Percent(int pct) -> sum.multiply(BigDecimal.valueOf(100 - pct)).movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
        };
    }

    public static void main(String[] args) {
        BigDecimal sum = new BigDecimal("598.00");
        for (Discount d : new Discount[]{new None(), new Minus(new BigDecimal("20")), new Minus(new BigDecimal("999")), new Percent(10)})
            System.out.println("  " + d + " → " + apply(d, sum));
        try {
            apply(null, sum);
        } catch (NullPointerException e) {
            System.out.println("  switch(null)，没有 case null → NullPointerException");
        }
    }
}
