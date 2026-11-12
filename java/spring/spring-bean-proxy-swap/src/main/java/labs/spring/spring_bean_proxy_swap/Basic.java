package labs.spring.spring_bean_proxy_swap;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场景 1：订单服务正常启动。看三件事：
 * ① orderService 在“初始化前”和“初始化后”分别是什么类；
 * ② @PostConstruct 里调本类的 @Transactional 方法，有没有事务；
 * ③ 启动完成以后，从容器拿到的引用去调，有没有事务。
 */
@Configuration
@EnableAutoConfiguration
public class Basic {

    public static class OrderService {
        @Transactional
        public boolean pay(long orderId) { return Tx.active(); }

        @PostConstruct
        void warmUp() {   // 很多人在这里预热缓存、补偿未完成订单
            Tx.log("③ @PostConstruct 里 this.pay()：事务已开启=" + pay(9527) + "，this 的类=" + Tx.cls(this));
        }
    }

    /** 不实现 Ordered：排在所有 BeanPostProcessor 的最后，看到的是别人处理完的结果。 */
    public static class SwapWatcher implements BeanPostProcessor {
        @Override public Object postProcessBeforeInitialization(Object bean, String name) {
            if (name.equals("orderService")) Tx.log("② 初始化前（@PostConstruct 之前）：" + Tx.cls(bean));
            return bean;
        }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (name.equals("orderService")) Tx.log("④ 初始化后（最后一步）：" + Tx.cls(bean));
            return bean;
        }
    }

    public static class ReadyRunner {
        private final OrderService orderService;
        ReadyRunner(OrderService orderService) { this.orderService = orderService; }
        @EventListener(ApplicationReadyEvent.class)
        void onReady() {
            Tx.log("⑤ ApplicationReadyEvent 里 orderService.pay()：事务已开启=" + orderService.pay(9527)
                    + "，引用的类=" + Tx.cls(orderService));
        }
    }

    @Bean static SwapWatcher swapWatcher() { return new SwapWatcher(); }   // BPP 用 static @Bean，不拖着配置类提前建
    @Bean OrderService orderService() {
        Tx.log("① new OrderService()");
        return new OrderService();
    }
    @Bean ReadyRunner readyRunner(OrderService orderService) { return new ReadyRunner(orderService); }
}
