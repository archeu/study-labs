package labs.design.ddd_bounded_context_aggregate.promotion;

/** 营销上下文 · 聚合：某个用户手里的一张券。不变量：一张券只能核销一次。 */
public class Coupon {
    public enum Status { UNUSED, LOCKED, REDEEMED }

    private final String id;
    private final int percent;              // 90 = 9 折
    private Status status = Status.UNUSED;
    private String lockedBy;                // 被哪张订单锁住

    public Coupon(String id, int percent) { this.id = id; this.percent = percent; }

    public void lock(String orderId) {
        if (status != Status.UNUSED) throw new IllegalStateException("券 " + id + " 现在是 " + status + "，不能再锁");
        status = Status.LOCKED; lockedBy = orderId;
    }

    /** 核销。同一张订单重复来，算成功但什么都不做（幂等）；别的订单来，拒绝。 */
    public boolean redeem(String orderId) {
        if (status == Status.REDEEMED && orderId.equals(lockedBy)) return false;
        if (status != Status.LOCKED || !orderId.equals(lockedBy))
            throw new IllegalStateException("券 " + id + " 没有被订单 " + orderId + " 锁住");
        status = Status.REDEEMED;
        return true;
    }

    public long apply(long amount) { return (amount * percent + 50) / 100; }
    public String id() { return id; }
    public Status status() { return status; }
}
