package labs.observability.logs_metrics_traces;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;
import java.util.Map;

@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final RestClient product;
    private final JdbcTemplate jdbc;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final ThreadPoolTaskExecutor rawPool;
    private final ThreadPoolTaskExecutor ctxPool;
    private final SpanStore spans;

    OrderController(RestClient product, JdbcTemplate jdbc, ObservationRegistry observations, MeterRegistry meters,
                    Tracer tracer, ThreadPoolTaskExecutor rawPool, ThreadPoolTaskExecutor ctxPool, SpanStore spans) {
        this.product = product;
        this.jdbc = jdbc;
        this.observations = observations;
        this.meters = meters;
        this.tracer = tracer;
        this.rawPool = rawPool;
        this.ctxPool = ctxPool;
        this.spans = spans;
    }

    /** 假装是商品服务：查一下价格，花 15 ms。 */
    @GetMapping("/api/products/{sku}")
    Map<String, Object> product(@PathVariable int sku) throws InterruptedException {
        Thread.sleep(15);
        log.info("product {} looked up", sku);
        return Map.of("sku", sku, "price", 399);
    }

    /**
     * 下单。mine 参数用来埋雷：
     *   async       通知任务交给普通线程池，看日志里的 traceId 还在不在
     *   cardinality 把订单号放进指标标签，看时间序列有多少条
     */
    @PostMapping("/api/orders")
    Map<String, Object> create(@RequestParam long userId, @RequestParam int sku, @RequestParam int qty,
                               @RequestParam(defaultValue = "none") String mine) {
        // 1. 调商品服务：出站 HTTP，自动有一个 CLIENT span，请求头里带 traceparent
        Map<?, ?> p = product.get().uri("/api/products/{sku}", sku).retrieve().body(Map.class);

        // 2. 写库：自己包一个 Observation，同时得到一个计时器指标和一个 span
        long orderId = Observation.createNotStarted("order.insert", observations)
                .lowCardinalityKeyValue("table", "orders")
                .observe(() -> insert(userId, sku, qty));

        // 3. 业务计数：标签只放取值有限的东西
        meters.counter("orders.placed", "channel", "app").increment();
        if ("cardinality".equals(mine)) {
            meters.counter("orders.placed.by.id", "orderId", String.valueOf(orderId)).increment();
        }

        // 4. 异步通知：换线程以后，上下文要靠装饰器带过去
        ThreadPoolTaskExecutor pool = "async".equals(mine) ? rawPool : ctxPool;
        pool.execute(() -> log.info("notify user {} about order {}", userId, orderId));

        log.info("order {} created for user {}, price {}", orderId, userId, p.get("price"));
        return Map.of("orderId", orderId, "traceId", tracer.currentSpan().context().traceId());
    }

    @GetMapping(value = "/debug/trace/{traceId}", produces = MediaType.TEXT_PLAIN_VALUE)
    String trace(@PathVariable String traceId) {
        return spans.render(traceId);
    }

    private long insert(long userId, int sku, int qty) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO orders(user_id, sku, qty) VALUES (?, ?, ?)", new String[] {"ID"});
            ps.setLong(1, userId);
            ps.setInt(2, sku);
            ps.setInt(3, qty);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
