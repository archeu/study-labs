package labs.design.ddd_bounded_context_aggregate.promotion;

import java.util.HashSet;
import java.util.Set;

/** 营销上下文 · 聚合：一个满减活动。这里的“优惠”是一条规则，运营随时会改。 */
public class Promotion {
    private final String id;
    private long threshold, minus;          // 满多少、减多少（分）
    private final long budget;              // 总预算
    private long used;                      // 已经花掉的
    private boolean ended;
    private final Set<String> countedOrders = new HashSet<>();   // 记过账的订单，防事件重复投递

    public Promotion(String id, long threshold, long minus, long budget) {
        this.id = id; this.threshold = threshold; this.minus = minus; this.budget = budget;
    }

    /** 运营改规则：只影响以后的算价。 */
    public void change(long threshold, long minus) { this.threshold = threshold; this.minus = minus; }

    /** 这一车够不够门槛，够的话减多少。活动结束了就是 0。 */
    public long minusFor(long total) { return !ended && total >= threshold ? minus : 0; }

    /** 记一笔预算。不变量：同一张订单只记一次；花超了就结束活动。返回这次是否真的记了账。 */
    public boolean consume(String orderId, long amount) {
        if (!countedOrders.add(orderId)) return false;
        used += amount;
        if (used >= budget) ended = true;
        return true;
    }

    public String id() { return id; }
    public long used() { return used; }
    public long budget() { return budget; }
    public boolean ended() { return ended; }
}
