package labs.cloud.sentinel_circuit_breaker.order;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 订单服务：20 个线程不停地调 reserve，按秒统计结果；熔断器状态变化也记下时刻。 */
@SpringBootApplication
@EnableFeignClients
@RestController
public class OrderApp {
    public static void main(String[] args) {
        new SpringApplicationBuilder(OrderApp.class).properties("spring.config.name=order").run(args);
    }

    static final String RESOURCE = "POST:http://inventory-svc/stock/reserve";
    private final InventoryClient inventory;
    @Value("${lab.breaker:true}") boolean breaker;
    private volatile long runStart;
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    public OrderApp(InventoryClient inventory) { this.inventory = inventory; }

    /** 全景第 10 站的规则：RT 超过 500 ms 算慢；1 秒窗口里至少 10 个请求、慢的占比 > 40% 就熔断 10 秒 */
    @EventListener(ApplicationReadyEvent.class)
    public void rules() {
        if (!breaker) return;
        DegradeRule r = new DegradeRule(RESOURCE)
                .setGrade(RuleConstant.DEGRADE_GRADE_RT)      // 慢调用比例
                .setCount(500)                                 // 慢调用线：RT > 500 ms
                .setSlowRatioThreshold(0.4)
                .setMinRequestAmount(10)
                .setStatIntervalMs(1000)
                .setTimeWindow(10);                            // 打开后 10 秒进入半开
        DegradeRuleManager.loadRules(List.of(r));
        EventObserverRegistry.getInstance().addStateChangeObserver("lab", (prev, next, rule, snapshot) ->
                events.add(String.format("%5.1f s 熔断器 %s → %s%s", sec(), prev, next,
                        snapshot == null ? "" : String.format("（慢调用占比 %.0f%%）", snapshot * 100))));
    }

    double sec() { return (System.currentTimeMillis() - runStart) / 1000.0; }

    @PostMapping("/demo/mark")
    public void mark(@RequestParam("text") String text) { events.add(String.format("%5.1f s ── %s", sec(), text)); }

    @GetMapping("/demo/run")
    public String run(@RequestParam("seconds") int seconds, @RequestParam("threads") int threads) throws Exception {
        events.clear();
        runStart = System.currentTimeMillis();
        int n = seconds;
        ConcurrentHashMap<String, AtomicLong>[] cnt = new ConcurrentHashMap[n];
        AtomicLong[] latSum = new AtomicLong[n], latN = new AtomicLong[n];
        for (int i = 0; i < n; i++) { cnt[i] = new ConcurrentHashMap<>(); latSum[i] = new AtomicLong(); latN[i] = new AtomicLong(); }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) pool.submit(() -> {
            while (true) {
                long s = System.currentTimeMillis();
                int sec = (int) ((s - runStart) / 1000);
                if (sec >= n) return;
                String r;
                try { r = inventory.reserve(9527, 88, 2); } catch (Exception e) { r = "ERR"; }
                long e = System.currentTimeMillis();
                int es = (int) Math.min(n - 1, (e - runStart) / 1000);
                cnt[es].computeIfAbsent(r, k -> new AtomicLong()).incrementAndGet();
                latSum[es].addAndGet(e - s); latN[es].incrementAndGet();
                try { Thread.sleep(10); } catch (InterruptedException ie) { return; }
            }
        });
        pool.shutdown();
        pool.awaitTermination(seconds + 10, TimeUnit.SECONDS);
        StringBuilder sb = new StringBuilder("秒   完成数  平均耗时  结果（端口=成功，FALLBACK=降级）\n");
        for (int i = 0; i < n; i++) {
            long c = latN[i].get();
            sb.append(String.format("%02d  %6d  %6d ms  %s%n", i, c, c == 0 ? 0 : latSum[i].get() / c, new TreeMap<>(cnt[i])));
        }
        sb.append("事件：\n");
        synchronized (events) { events.forEach(x -> sb.append("  ").append(x).append('\n')); }
        return sb.toString();
    }
}
