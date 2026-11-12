package labs.spring.spring_bean_proxy_swap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.Transactional;

/** 场景 2～5：循环依赖碰上代理。用 @Bean 的声明顺序固定“谁先建”。字段注入，否则连三级缓存都救不了。 */
public class Cycles {

    public static class OrderService {
        @Autowired InventoryService inventory;
        @Transactional public boolean pay(long id) { return Tx.active(); }
        public InventoryService inventory() { return inventory; }
    }
    public static class InventoryService {
        @Autowired OrderService order;
        @Transactional public void deduct(long sku) {}
        // 别直接读 inv.order：inv 是 CGLIB 代理，代理对象自己的字段全是 null，要走方法转到原始对象上
        public OrderService order() { return order; }
    }

    /** 下单成功后发短信：@Async，又反过来要查订单。 */
    public static class NotifyService {
        @Autowired PayingOrderService order;
        @Async public void smsPaid(long id) {}
        public PayingOrderService order() { return order; }
    }
    public static class PayingOrderService {
        @Autowired NotifyService notify;
        @Transactional public boolean pay(long id) { return Tx.active(); }
        public NotifyService notifyService() { return notify; }
    }

    /** 付款接口：只依赖通知服务。 */
    public static class PayController {
        @Autowired NotifyService notify;
    }

    /** 2 和 3：订单 ⇄ 库存，两边都是 @Transactional。 */
    @Configuration @EnableAutoConfiguration
    public static class TxCycle {
        @Bean OrderService orderService() { return new OrderService(); }
        @Bean InventoryService inventoryService() { return new InventoryService(); }
    }

    /** 4：通知 ⇄ 订单，先建 @Async 的通知服务。 */
    @Configuration @EnableAutoConfiguration @EnableAsync
    public static class AsyncFirst {
        @Bean NotifyService notifyService() { return new NotifyService(); }
        @Bean PayingOrderService orderService() { return new PayingOrderService(); }
    }

    /** 5：同样两个类，只是先建订单服务。 */
    @Configuration @EnableAutoConfiguration @EnableAsync
    public static class AsyncSecond {
        @Bean PayingOrderService orderService() { return new PayingOrderService(); }
        @Bean NotifyService notifyService() { return new NotifyService(); }
    }

    /** 6：还是通知 ⇄ 订单，但第一个被建的是 Controller，它从通知服务这一侧走进了环。 */
    @Configuration @EnableAutoConfiguration @EnableAsync
    public static class AsyncViaController {
        @Bean PayController payController() { return new PayController(); }
        @Bean NotifyService notifyService() { return new NotifyService(); }
        @Bean PayingOrderService orderService() { return new PayingOrderService(); }
    }
}
