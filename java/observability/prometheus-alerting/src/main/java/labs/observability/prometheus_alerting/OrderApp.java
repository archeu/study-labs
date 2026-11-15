package labs.observability.prometheus_alerting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * 交易靶场 order-svc 的最小版，只看指标。run.sh 会起 3 个实例（18091～18093），相当于 order-1、2、3。
 * /admin/hold 模拟全景里的对账任务：开 N 个线程，各攥一条连接睡一段时间，把 HikariCP 的池子占满。
 */
@SpringBootApplication
@RestController
public class OrderApp {

    private static final Logger log = LoggerFactory.getLogger(OrderApp.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    OrderApp(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderApp.class, args);
    }

    /** 下单：拿连接，插一行。池子被占满时，这里等满 connectionTimeout（3 秒）后抛异常，返回 500。 */
    @PostMapping("/api/orders")
    Map<String, Object> create(@RequestParam long userId, @RequestParam int sku, @RequestParam int qty) {
        jdbc.update("INSERT INTO orders(user_id, sku, qty) VALUES (?, ?, ?)", userId, sku, qty);
        return Map.of("ok", true);
    }

    /** 雷：模拟“事务里等支付渠道”的对账任务，threads 个线程各攥一条连接 seconds 秒。 */
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
        return Map.of("holding", threads, "seconds", seconds);
    }
}
