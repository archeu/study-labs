package labs.observability.arthas_diagnosis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全景第 9 站的现场：order-svc 里有个对账任务，在 @Transactional 方法里调支付渠道查单。
 * 渠道一慢，10 个对账线程各攥着一条连接干等，下单拿不到连接，3 秒后 500。
 * 为了不多起进程，“支付渠道”也做在这个应用里（/channel/query），它的慢由 /admin/channel?delayMs= 控制。
 */
@SpringBootApplication
@RestController
public class OrderApp {

    private final JdbcTemplate jdbc;
    private final ReconcileService reconcile;
    private final ExecutorService reconPool = Executors.newFixedThreadPool(10, r -> {
        Thread t = new Thread(r, "recon-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger SEQ = new AtomicInteger();
    static volatile long channelDelayMs = 50;

    OrderApp(JdbcTemplate jdbc, ReconcileService reconcile) {
        this.jdbc = jdbc;
        this.reconcile = reconcile;
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderApp.class, args);
    }

    /** 调支付渠道用的客户端：JDK 的 HttpURLConnection，读超时 30 秒（这就是雷的一半）。 */
    @Bean
    static RestClient payChannelRestClient(RestClient.Builder b) {
        ClientHttpRequestFactorySettings s = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(1)).withReadTimeout(Duration.ofSeconds(30));
        return b.baseUrl("http://localhost:18111").requestFactory(ClientHttpRequestFactoryBuilder.simple().build(s)).build();
    }

    @PostMapping("/api/orders")
    Map<String, Object> create(@RequestParam long userId, @RequestParam int sku, @RequestParam int qty) {
        jdbc.update("INSERT INTO orders(user_id, sku, qty, status) VALUES (?, ?, ?, 'UNPAID')", userId, sku, qty);
        return Map.of("ok", true);
    }

    /** 假的支付渠道：按设定的延迟才回。 */
    @GetMapping("/channel/query")
    Map<String, Object> channelQuery(@RequestParam long orderId) throws InterruptedException {
        Thread.sleep(channelDelayMs);
        return Map.of("orderId", orderId, "paid", orderId % 3 != 0);
    }

    @PostMapping("/admin/channel")
    Map<String, Object> setChannelDelay(@RequestParam long delayMs) {
        channelDelayMs = delayMs;
        return Map.of("delayMs", delayMs);
    }

    /** 相当于 03:00 的 @Scheduled：10 个线程并行对账 count 笔。 */
    @PostMapping("/admin/reconcile")
    Map<String, Object> startReconcile(@RequestParam int count) {
        for (long id = 1; id <= count; id++) {
            long orderId = id;
            reconPool.execute(() -> reconcile.reconcileOne(orderId));
        }
        return Map.of("submitted", count);
    }
}
