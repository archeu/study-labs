package labs.spring.spring_aop_chain;

import java.util.Arrays;
import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 全景第 6 站：Controller 调 orderService.pay() 时，代理里那几层在干什么。
 * 场景：chain plain stack timed self async order0（不带参数就全跑）。
 */
@SpringBootApplication
@EnableAsync
public class ChainLab {

    public static void main(String[] args) throws Exception {
        List<String> runs = args.length > 0 ? Arrays.asList(args)
                : List.of("chain", "plain", "stack", "timed", "self", "async", "order0");
        for (String r : runs) {
            System.out.println("\n=== " + r + " ===");
            switch (r) {
                case "chain" -> chain();
                case "plain" -> { try (var ctx = start("--lab.timing=false")) {
                    printAdvisors("orderService", ctx.getBean("orderService"));
                    var c = ctx.getBean(OrderController.class);
                    for (int i = 0; i < 20; i++) c.pay(9527);   // 热身：JDK 17 反射调用 15 次后换成生成的字节码
                    StackDump.enabled = true;
                    System.out.println("    结果：" + c.pay(9527)); } }
                case "stack" -> { try (var ctx = start()) { StackDump.enabled = true;
                    System.out.println("    结果：" + ctx.getBean(OrderController.class).pay(9527)); } }
                case "timed" -> { try (var ctx = start()) { StackDump.enabled = true;
                    System.out.println("    结果：" + ctx.getBean(OrderController.class).payTimed(9527)); } }
                case "self" -> { try (var ctx = start()) { StackDump.enabled = true;
                    System.out.println("    结果：" + ctx.getBean(OrderController.class).payViaThis(9527)); } }
                case "async" -> { try (var ctx = start()) {
                    printAdvisors("notifyService", ctx.getBean("notifyService"));
                    System.out.println("    调用方线程=" + Thread.currentThread().getName());
                    System.out.println("    结果：" + ctx.getBean(OrderController.class).notifyPaid(9527)); } }
                case "order0" -> { try (var ctx = start("--lab.timing-order=0")) {
                    printAdvisors("orderService", ctx.getBean("orderService"));
                    System.out.println("    结果：" + ctx.getBean(OrderController.class).payTimed(9527)); } }
                default -> System.out.println("不认识的场景：" + r);
            }
            StackDump.enabled = false;
        }
    }

    /** 默认顺序下，orderService 和 notifyService 的代理里各挂了哪些 Advisor。 */
    static void chain() {
        try (var ctx = start()) {
            printAdvisors("orderService", ctx.getBean("orderService"));
            System.out.println("    timed 场景里计时切面和事务谁在外面：");
            ctx.getBean(OrderController.class).payTimed(9527);
        }
    }

    static void printAdvisors(String name, Object bean) {
        System.out.printf("    %s 的类：%s（CGLIB 代理=%s）%n", name,
                bean.getClass().getSimpleName(), AopUtils.isCglibProxy(bean));
        Advisor[] advisors = ((Advised) bean).getAdvisors();
        for (int i = 0; i < advisors.length; i++) {
            Advisor a = advisors[i];
            String what = a.getAdvice().getClass().getSimpleName();
            System.out.printf("    链上第 %d 个：%s → %s%n", i, a.getClass().getSimpleName(), what);
        }
    }

    static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(ChainLab.class).run(args);
    }
}
