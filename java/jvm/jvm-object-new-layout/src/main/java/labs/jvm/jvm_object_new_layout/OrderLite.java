package labs.jvm.jvm_object_new_layout;

/** 练习用：把字段换小一点，看对象能省多少（↺ 练习 1 的答案之一） */
public class OrderLite {
    long id;
    int userId;      // 用户数不超过 21 亿
    int skuId;
    int amountCents; // 单笔不超过 2147 万元
    short qty;
    byte status;     // 0 待支付 1 已支付 2 已取消，替代 String

    OrderLite(long id, int userId, int skuId, short qty, int amountCents, byte status) {
        this.id = id; this.userId = userId; this.skuId = skuId; this.qty = qty;
        this.amountCents = amountCents; this.status = status;
    }
}
