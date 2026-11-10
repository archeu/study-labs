package labs.java.pano_j03_object_new_to_gc;

/** 交易靶场的订单：对象头 12 字节 + 这几个字段 */
public class Order {
    long id;
    long userId;
    int skuId;
    int qty;
    long amountCents;
    String status;

    Order(long id, long userId, int skuId, int qty, long amountCents, String status) {
        this.id = id; this.userId = userId; this.skuId = skuId; this.qty = qty;
        this.amountCents = amountCents; this.status = status;
    }
}
