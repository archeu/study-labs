package labs.jvm.jvm_classloading;

/** 交易靶场的订单：全景第 1 站第一次 new 它时，要先把 Order.class 读进来。 */
public class Order {
    static {
        System.out.println("  [Order 的 static 块执行了：类初始化，只会有一次] loader=" + Order.class.getClassLoader());
    }
    final long id;
    final int qty;
    public Order(long id, int qty) { this.id = id; this.qty = qty; }
    @Override public String toString() { return "Order#" + id + "×" + qty; }
}
