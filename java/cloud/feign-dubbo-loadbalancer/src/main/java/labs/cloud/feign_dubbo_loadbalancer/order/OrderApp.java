package labs.cloud.feign_dubbo_loadbalancer.order;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.*;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** 订单服务：用 OpenFeign 调 inventory-svc，提供几个演示接口给 run.sh 调。 */
@SpringBootApplication
@EnableFeignClients
@RestController
public class OrderApp {
    public static void main(String[] args) {
        new SpringApplicationBuilder(OrderApp.class).properties("spring.config.name=order").run(args);
    }

    private final InventoryClient inventory;
    /** 第一次 LoadBalancer 开始挑实例时的调用栈 */
    static final AtomicReference<String> FIRST_STACK = new AtomicReference<>();

    public OrderApp(InventoryClient inventory) { this.inventory = inventory; }

    /** LoadBalancer 的生命周期回调：在它开始挑实例的那一刻抓一次调用栈 */
    @Bean
    LoadBalancerLifecycle<Object, Object, ServiceInstance> stackGrabber() {
        return new LoadBalancerLifecycle<>() {
            @Override public void onStart(Request<Object> request) {
                if (FIRST_STACK.get() != null) return;
                String s = Arrays.stream(new Throwable().getStackTrace())
                        .map(StackTraceElement::toString)
                        .filter(f -> f.contains("feign") || f.contains("loadbalancer") || f.contains("OrderApp") || f.contains("$Proxy"))
                        .map(f -> f.replaceAll("\\(.*\\)", "").replace("org.springframework.cloud.", "o.s.c."))
                        .limit(14).collect(Collectors.joining("\n"));
                FIRST_STACK.compareAndSet(null, s);
            }
            @Override public void onStartRequest(Request<Object> request, Response<ServiceInstance> lbResponse) { }
            @Override public void onComplete(CompletionContext<Object, ServiceInstance, Object> ctx) { }
        };
    }

    @GetMapping("/demo/once")
    public String once() {
        String port = inventory.reserve(9527, 88, 2);
        return "inventoryClient 的类：" + inventory.getClass().getName()
                + "\nInvocationHandler：" + Proxy.getInvocationHandler(inventory).getClass().getName()
                + "\n这次落到：" + port
                + "\nLoadBalancer 开始挑实例时的调用栈（只留 feign / loadbalancer 相关的帧，栈顶在上）：\n" + FIRST_STACK.get();
    }

    /** 连续调 n 次：每台各接了多少、平均多久 */
    @GetMapping("/demo/burst")
    public String burst(@RequestParam("n") int n) {
        Map<String, long[]> m = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            String port;
            try { port = inventory.reserve(9527, 88, 2); } catch (Exception e) { port = "失败"; }
            long[] a = m.computeIfAbsent(port, k -> new long[2]);
            a[0]++; a[1] += System.nanoTime() - t;
        }
        return m.entrySet().stream().map(e -> e.getKey() + "：" + e.getValue()[0] + " 次，平均 "
                + e.getValue()[1] / e.getValue()[0] / 1_000_000 + " ms").collect(Collectors.joining("\n"));
    }

    /** 每 20 ms 调一次，持续 seconds 秒；按秒统计落到各端口的次数（失败记作 X） */
    @GetMapping("/demo/run")
    public String run(@RequestParam("seconds") int seconds) throws InterruptedException {
        long start = System.currentTimeMillis();
        List<Map<String, Integer>> perSec = new ArrayList<>();
        for (int s = 0; s < seconds; s++) perSec.add(new TreeMap<>());
        while (true) {
            long now = System.currentTimeMillis();
            int sec = (int) ((now - start) / 1000);
            if (sec >= seconds) break;
            String port;
            try { port = inventory.reserve(9527, 88, 2); } catch (Exception e) { port = "X"; }
            perSec.get(sec).merge(port, 1, Integer::sum);
            Thread.sleep(20);
        }
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < seconds; s++) sb.append(String.format("%02d s %s%n", s, perSec.get(s)));
        return sb.toString();
    }
}
