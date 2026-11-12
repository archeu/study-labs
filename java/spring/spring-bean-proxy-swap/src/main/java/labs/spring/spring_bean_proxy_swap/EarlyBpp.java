package labs.spring.spring_bean_proxy_swap;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.PriorityOrdered;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场景 7、8：一个做审计的 BeanPostProcessor 依赖了订单服务。
 * BeanPostProcessor 要在普通 bean 之前建好，它依赖的 orderService 只好跟着提前建。
 * 这时还没登记的 BeanPostProcessor 都处理不到它；负责换代理的那个（自动代理创建器）在不在其中，看登记顺序：
 * PriorityOrdered 一组 → Ordered 一组（自动代理创建器在这组）→ 其余。
 */
public class EarlyBpp {

    public static class OrderService {
        @Transactional public boolean pay(long id) { return Tx.active(); }
    }

    /** 普通 BeanPostProcessor：排在第三组，自动代理创建器已经登记了。 */
    public static class AuditBpp implements BeanPostProcessor {
        final OrderService orderService;
        AuditBpp(OrderService orderService) { this.orderService = orderService; }
    }

    /** 想让它“最先执行”，加了 PriorityOrdered：排进第一组，比自动代理创建器还早。 */
    public static class FirstAuditBpp extends AuditBpp implements PriorityOrdered {
        FirstAuditBpp(OrderService orderService) { super(orderService); }
        @Override public int getOrder() { return 0; }
    }

    @Configuration @EnableAutoConfiguration
    public static class Plain {
        @Bean static OrderService orderService() { return new OrderService(); }
        @Bean static AuditBpp auditBpp(OrderService orderService) { return new AuditBpp(orderService); }
    }

    @Configuration @EnableAutoConfiguration
    public static class Priority {
        @Bean static OrderService orderService() { return new OrderService(); }
        @Bean static FirstAuditBpp auditBpp(OrderService orderService) { return new FirstAuditBpp(orderService); }
    }
}
