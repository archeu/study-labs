package labs.javase.oop_generics_erasure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 商品：不会变的值，用 record（JDK 16 起正式可用）。 */
record Product(long id, String name, BigDecimal price) {}

/** 订单行：不会变的值。 */
record OrderLine(long skuId, String name, BigDecimal price, int qty) {
    BigDecimal amount() { return price.multiply(BigDecimal.valueOf(qty)); }
}

enum Status { CREATED, PAID, CANCELLED }

/**
 * 订单：有身份、状态会变，用普通类。
 * 封装：字段全私有，状态只能经 pay()/cancel() 按规则改，订单行对外只读。
 */
class Order {
    private final long id;
    private final long userId;
    private final List<OrderLine> lines;
    private Status status = Status.CREATED;

    Order(long id, long userId, List<OrderLine> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("订单至少一行");
        this.id = id;
        this.userId = userId;
        this.lines = new ArrayList<>(lines);       // 防御性复制：外面再改原列表，影响不到订单
    }

    long id() { return id; }
    long userId() { return userId; }
    Status status() { return status; }
    List<OrderLine> lines() { return Collections.unmodifiableList(lines); }

    BigDecimal sum() {
        return lines.stream().map(OrderLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    void pay() {
        if (status != Status.CREATED) throw new IllegalStateException(id + " 当前是 " + status + "，不能付款");
        status = Status.PAID;
    }

    void cancel() {
        if (status == Status.PAID) throw new IllegalStateException(id + " 已付款，不能直接取消");
        status = Status.CANCELLED;
    }
}

/**
 * 优惠：一个接口，几种实现。sealed 把实现限定在这几个里（JDK 17 正式可用）。
 * JDK 17 还不能在 switch 里按类型拆 record（那是 21），所以每种优惠自己实现 apply，靠多态分派。
 */
sealed interface Discount permits Discount.None, Discount.Minus, Discount.Percent, ThresholdDiscount {
    BigDecimal apply(BigDecimal sum);

    /** 默认方法（JDK 8 起）：接口可以带实现，但不能带实例字段。 */
    default String label() { return getClass().getSimpleName(); }

    record None() implements Discount {
        public BigDecimal apply(BigDecimal sum) { return sum; }
    }

    record Minus(BigDecimal off) implements Discount {
        public BigDecimal apply(BigDecimal sum) { return sum.subtract(off).max(BigDecimal.ZERO); }
    }

    record Percent(int pct) implements Discount {
        public BigDecimal apply(BigDecimal sum) {
            return sum.multiply(BigDecimal.valueOf(100 - pct)).movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}

/**
 * 抽象类：可以有字段、构造器，把“满多少才减”的骨架写死（模板方法），差异留给子类。
 * 接口做不到这一点：接口没有实例字段，也没有构造器。
 */
abstract non-sealed class ThresholdDiscount implements Discount {
    protected final BigDecimal threshold;

    protected ThresholdDiscount(BigDecimal threshold) {
        this.threshold = threshold;
        System.out.println("    [构造中] " + describe());   // 坑：构造器里调了子类能覆盖的方法
    }

    @Override
    public final BigDecimal apply(BigDecimal sum) {
        return sum.compareTo(threshold) >= 0 ? reduce(sum) : sum;
    }

    protected abstract BigDecimal reduce(BigDecimal sum);

    protected String describe() { return "满 " + threshold; }
}

/** 满 300 减 30。 */
class FullReduction extends ThresholdDiscount {
    private final BigDecimal off;

    FullReduction(BigDecimal threshold, BigDecimal off) {
        super(threshold);                // 父类构造器先跑，这时 off 还是 null
        this.off = off;
    }

    @Override
    protected BigDecimal reduce(BigDecimal sum) { return sum.subtract(off); }

    @Override
    protected String describe() { return "满 " + threshold + " 减 " + off; }
}
