package labs.jvm.jvm_troubleshooting;

/** 订单。新下的单带一份 1 KB 的“下单快照”（收货地址、商品快照之类序列化后的字节）。 */
public class Order {
    final long id;
    final long userId;
    final int skuId;
    final int qty;
    final long amountCents;
    final byte[] snapshot;

    Order(long id, long userId, int skuId, int qty, long amountCents, boolean withSnapshot) {
        this.id = id;
        this.userId = userId;
        this.skuId = skuId;
        this.qty = qty;
        this.amountCents = amountCents;
        this.snapshot = withSnapshot ? new byte[1024] : null;
    }

    void appendCsv(StringBuilder sb) {
        sb.append(id).append(',').append(userId).append(',').append(skuId).append(',')
          .append(qty).append(',').append(amountCents / 100).append('.')
          .append(amountCents % 100 < 10 ? "0" : "").append(amountCents % 100)
          .append(",PAID,2026-11-10 20:00:00\n");
    }
}
