package labs.observability.tracing_otel_skywalking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 同一个 jar 起两个进程：order-svc（18101）和 product-svc（18102）。
 * 代码里没有一行链路相关的代码，也没有链路依赖：span、traceparent 头、跨线程传上下文，全是 OpenTelemetry Java agent 加的。
 */
@SpringBootApplication
@RestController
public class TracingApp {

    private static final Logger log = LoggerFactory.getLogger(TracingApp.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final RestClient product;
    /** 普通的 JDK 线程池，没加任何装饰器。 */
    private final ExecutorService notifyPool = Executors.newFixedThreadPool(2);

    TracingApp(JdbcTemplate jdbc, DataSource dataSource, RestClient product) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.product = product;
    }

    public static void main(String[] args) {
        SpringApplication.run(TracingApp.class, args);
    }

    @Bean
    static RestClient productClient(RestClient.Builder b, @Value("${product.url:http://localhost:18102}") String url) {
        return b.baseUrl(url).build();
    }

    /** 商品服务：查一次库。 */
    @GetMapping("/api/products/{sku}")
    Map<String, Object> product(@PathVariable int sku) {
        Integer price = jdbc.queryForObject("SELECT price FROM products WHERE sku = ?", Integer.class, sku);
        return Map.of("sku", sku, "price", price == null ? 0 : price);
    }

    /** 下单：调商品服务 → 写订单 → 异步通知。 */
    @PostMapping("/api/orders")
    Map<String, Object> create(@RequestParam long userId, @RequestParam int sku, @RequestParam int qty) {
        Map<?, ?> p = product.get().uri("/api/products/{sku}", sku).retrieve().body(Map.class);
        jdbc.update("INSERT INTO orders(user_id, sku, qty) VALUES (?, ?, ?)", userId, sku, qty);
        notifyPool.execute(() -> {
            jdbc.update("INSERT INTO notices(user_id, msg) VALUES (?, ?)", userId, "order created");
            log.info("notify user {}", userId);
        });
        return Map.of("ok", true, "price", p.get("price"));
    }

    /** 雷：模拟全景里的对账线程，threads 个线程各攥一条连接 seconds 秒。 */
    @PostMapping("/admin/hold")
    Map<String, Object> hold(@RequestParam int threads, @RequestParam int seconds) {
        for (int i = 1; i <= threads; i++) {
            Thread t = new Thread(() -> {
                try (Connection c = dataSource.getConnection()) {
                    Thread.sleep(seconds * 1000L);
                } catch (Exception e) {
                    log.warn("hold failed: {}", e.toString());
                }
            }, "recon-" + i);
            t.setDaemon(true);
            t.start();
        }
        return Map.of("holding", threads);
    }
}
