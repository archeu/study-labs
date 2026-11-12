package labs.spring.spring_bean_proxy_swap;

import java.util.List;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 交易靶场·订单服务的启动装配（全景 J05 第 1 站），九个场景各起一次容器：
 *   basic        正常启动：代理在哪一步换进去，@PostConstruct 里有没有事务
 *   cycle-banned 订单 ⇄ 库存，Boot 默认设置
 *   cycle-tx     同上，打开 allow-circular-references
 *   async-first  通知(@Async) ⇄ 订单，先建通知
 *   async-second 同样两个类，先建订单
 *   async-via-controller 先建依赖通知服务的 Controller
 *   early-bpp    普通 BeanPostProcessor 依赖了订单服务
 *   early-bpp-priority  同上，但它实现了 PriorityOrdered
 * 运行：mvn -q -pl spring/spring-bean-proxy-swap compile exec:java -Dexec.mainClass=labs.spring.spring_bean_proxy_swap.ProxySwapLab [-Dexec.args="basic"]
 */
public class ProxySwapLab {

    public static void main(String[] args) {
        List<String> only = List.of(args);
        run(only, "basic", Basic.class, false, ctx -> {
            var bf = (AbstractBeanFactory) ctx.getBeanFactory();
            Tx.log("容器里的 BeanPostProcessor，按执行顺序：");
            bf.getBeanPostProcessors().forEach(p -> Tx.log("    " + Tx.cls(p)));
        });
        run(only, "cycle-banned", Cycles.TxCycle.class, false, ctx -> {});
        run(only, "cycle-tx", Cycles.TxCycle.class, true, ctx -> {
            var order = ctx.getBean(Cycles.OrderService.class);
            var inv = ctx.getBean(Cycles.InventoryService.class);
            Tx.log("容器里的 orderService：" + Tx.cls(order));
            Tx.log("库存服务手里的 order：" + Tx.cls(inv.order()) + "，和容器里是同一个对象=" + (inv.order() == order));
            Tx.log("通过库存服务手里的引用调 pay()：事务已开启=" + inv.order().pay(9527));
        });
        Check asyncPair = ctx -> {
            var notify = ctx.getBean(Cycles.NotifyService.class);
            var order = ctx.getBean(Cycles.PayingOrderService.class);
            Tx.log("容器里的 notifyService：" + Tx.cls(notify) + "；订单服务手里的 notify：" + Tx.cls(order.notifyService())
                    + "，同一个=" + (order.notifyService() == notify));
            Tx.log("容器里的 orderService：" + Tx.cls(order) + "；通知服务手里的 order：" + Tx.cls(notify.order())
                    + "，同一个=" + (notify.order() == order));
        };
        run(only, "async-first", Cycles.AsyncFirst.class, true, asyncPair);
        run(only, "async-second", Cycles.AsyncSecond.class, true, asyncPair);
        run(only, "async-via-controller", Cycles.AsyncViaController.class, true, asyncPair);
        Check earlyCheck = ctx -> {
            var order = ctx.getBean(EarlyBpp.OrderService.class);
            Tx.log("容器里的 orderService：" + Tx.cls(order) + "；调 pay()：事务已开启=" + order.pay(9527));
        };
        run(only, "early-bpp", EarlyBpp.Plain.class, false, earlyCheck);
        run(only, "early-bpp-priority", EarlyBpp.Priority.class, false, earlyCheck);
    }

    interface Check { void on(ConfigurableApplicationContext ctx); }

    static void run(List<String> only, String name, Class<?> config, boolean allowCycles, Check check) {
        if (!only.isEmpty() && !only.contains(name)) return;
        System.out.println("\n=== " + name + (allowCycles ? "（allow-circular-references=true）" : ""));
        long t0 = System.nanoTime();
        try (var ctx = new SpringApplicationBuilder(config)
                .web(WebApplicationType.NONE)
                .properties("spring.main.allow-circular-references=" + allowCycles)
                .run()) {
            Tx.log("启动成功，用时 " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            check.on(ctx);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            String msg = String.valueOf(root.getMessage());
            Tx.log("启动失败：" + root.getClass().getSimpleName());
            Tx.log("  " + (msg.length() > 420 ? msg.substring(0, 420) + " …" : msg));
        }
    }
}
