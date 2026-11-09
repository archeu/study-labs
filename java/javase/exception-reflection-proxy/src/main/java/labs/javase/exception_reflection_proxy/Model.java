package labs.javase.exception_reflection_proxy;

import java.lang.annotation.*;
import java.math.BigDecimal;

/** 运行时可见：反射读得到。审计日志要记的业务动作名。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Audit {
    String value();
}

/** 只留在 class 文件里：运行时读不到。 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
@interface BuildOnly {}

/** 非受检：库存不够。全景第 7 站抛的就是它。 */
class OutOfStockException extends RuntimeException {
    OutOfStockException(long skuId, int qty) { super("库存不足：sku " + skuId + " 要 " + qty + " 件"); }
}

/** 受检：风控服务超时，调用方必须 catch 或声明。 */
class RiskCheckTimeoutException extends Exception {
    RiskCheckTimeoutException(String msg) { super(msg); }
}

interface OrderService {
    @Audit("下单")
    BigDecimal place(long userId, long skuId, int qty) throws RiskCheckTimeoutException;

    @Audit("查订单") @BuildOnly
    String find(long orderId);
}

class OrderServiceImpl implements OrderService {
    int stock = 50;

    @Override @Audit("下单")
    public BigDecimal place(long userId, long skuId, int qty) throws RiskCheckTimeoutException {
        if (userId == 4040) throw new RiskCheckTimeoutException("风控 800ms 没返回");
        if (qty > stock) throw new OutOfStockException(skuId, qty);
        stock -= qty;
        String self = find(9527);                     // 自己调自己：不经过代理
        return new BigDecimal("299.00").multiply(BigDecimal.valueOf(qty));
    }

    @Override @Audit("查订单") @BuildOnly
    public String find(long orderId) { return "Order#" + orderId; }
}
