package labs.java.pano_j01_order_object_life;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 第 2 站：请求体反序列化成这个 record。record 是 JDK 16 起的正式特性。 */
record PlaceOrderCmd(long userId, List<Item> items, String coupon) {
    record Item(long skuId, int qty) {}
}

/** 商品：本地缓存里的值，不可变。 */
record Product(long id, String name, BigDecimal price) {}

/** 订单行：值对象，不可变。 */
record OrderLine(long skuId, String name, BigDecimal price, int qty) {
    BigDecimal amount() { return price.multiply(BigDecimal.valueOf(qty)); }
}

/** 第 6 站：优惠是一个封闭的类型族，switch 能检查有没有漏掉哪种。 */
sealed interface Discount permits Discount.None, Discount.Minus, Discount.Percent {
    record None() implements Discount {}
    record Minus(BigDecimal off) implements Discount {}
    record Percent(int pct) implements Discount {}

    static Discount parse(String coupon) {
        if (coupon == null) return new None();
        if (coupon.startsWith("MINUS")) return new Minus(new BigDecimal(coupon.substring(5)));
        if (coupon.startsWith("OFF")) return new Percent(Integer.parseInt(coupon.substring(3)));
        return new None();
    }

    static BigDecimal apply(Discount d, BigDecimal sum) {
        return switch (d) {                       // JDK 21：switch 上的类型模式 + record 模式
            case None n -> sum;
            case Minus(BigDecimal off) -> sum.subtract(off).max(BigDecimal.ZERO);
            case Percent(int pct) -> sum.multiply(BigDecimal.valueOf(100 - pct)).movePointLeft(2);
        };
    }
}

enum Status { CREATED, PAID, CANCELLED }

/** 第 5 站：订单是实体，有身份、有状态变化，所以用普通类而不是 record。 */
class Order {
    private final long id;
    private final long userId;
    private final List<OrderLine> lines;
    private BigDecimal total;
    private Status status = Status.CREATED;
    private final Instant createdAt = Instant.now();
    private final String internalNote;            // 只给内部看，不发给前端

    Order(long id, long userId, List<OrderLine> lines, String internalNote) {
        this.id = id;
        this.userId = userId;
        this.lines = lines;
        this.internalNote = internalNote;
    }

    public long getId() { return id; }
    public long getUserId() { return userId; }
    public List<OrderLine> getLines() { return lines; }
    public BigDecimal getTotal() { return total; }
    public Status getStatus() { return status; }

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Instant getCreatedAt() { return createdAt; }

    @JsonIgnore
    public String getInternalNote() { return internalNote; }

    void setTotal(BigDecimal total) { this.total = total; }
    void cancel() { if (status == Status.CREATED) status = Status.CANCELLED; }
}
