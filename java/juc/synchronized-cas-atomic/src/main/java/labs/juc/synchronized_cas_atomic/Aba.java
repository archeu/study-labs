package labs.juc.synchronized_cas_atomic;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * ABA：订单状态 PAID → REFUNDING → PAID，值回到了原样，CAS 看不出中间变过。
 * 用确定的顺序演示，不靠碰运气。
 */
public class Aba {
    public static void main(String[] args) {
        AtomicReference<String> status = new AtomicReference<>("PAID");
        String seen = status.get();                                // 发货线程读到 PAID，准备改成 SHIPPED
        status.compareAndSet("PAID", "REFUNDING");                 // 这期间：用户申请退款
        status.compareAndSet("REFUNDING", "PAID");                 // 客服撤销退款，又回到 PAID
        boolean ok = status.compareAndSet(seen, "SHIPPED");
        System.out.println("AtomicReference：      CAS(PAID → SHIPPED) = " + ok + "，现在 " + status.get()
                + "（中间被退款过，它看不出来）");

        AtomicStampedReference<String> s2 = new AtomicStampedReference<>("PAID", 0);
        int[] stampHolder = new int[1];
        String seen2 = s2.get(stampHolder);
        int stamp = stampHolder[0];                                // 读到 PAID，版本 0
        s2.compareAndSet("PAID", "REFUNDING", 0, 1);
        s2.compareAndSet("REFUNDING", "PAID", 1, 2);
        boolean ok2 = s2.compareAndSet(seen2, "SHIPPED", stamp, stamp + 1);
        System.out.println("AtomicStampedReference：CAS(PAID@0 → SHIPPED) = " + ok2 + "，现在 " + s2.getReference()
                + "@" + s2.getStamp() + "（版本号变了，拒绝）");
    }
}
