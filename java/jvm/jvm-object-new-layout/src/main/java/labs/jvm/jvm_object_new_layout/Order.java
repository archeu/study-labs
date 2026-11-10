package labs.jvm.jvm_object_new_layout;

/** 交易靶场的订单，和 J03 全景页用的是同一个类 */
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
