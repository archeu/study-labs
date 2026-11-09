package demo;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class DemoApp {
    private final JdbcTemplate jdbc;
    private final Timer sql;                       // 只量“拿到连接以后”SQL 自己花的时间

    DemoApp(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.sql = registry.timer("demo.sql");
    }

    public static void main(String[] args) { SpringApplication.run(DemoApp.class, args); }

    /** 实验 1、2：每个请求占一个数据库连接 20 ms，模拟一条 20 ms 的 SQL */
    @GetMapping("/work")
    public String work() {
        jdbc.execute((ConnectionCallback<Object>) con -> {      // 进到这里时，已经从连接池拿到连接了
            long t0 = System.nanoTime();
            con.createStatement().execute("CALL SLEEP_MS(20)");
            sql.record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
            return null;
        });
        return "ok";
    }

    /** 实验 3：下单 = 插一行，再数一数这个用户有几单（user_id 上没有索引） */
    @PostMapping("/order")
    public Long order(@RequestParam long uid) {
        jdbc.update("INSERT INTO orders(user_id, amount) VALUES (?, 1)", uid);
        long t0 = System.nanoTime();
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, uid);
        sql.record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        return n;
    }

    /** 实验 3 收尾：给 user_id 补上索引（演示用，真实项目在数据库客户端里执行） */
    @PostMapping("/admin/add-index")
    public String addIndex() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_orders_user ON orders(user_id)");
        return "index added";
    }

    /** 选做：和 /work 一样占连接 20 ms，但每次往一个“只放不删”的本地缓存里塞 8 KB */
    static final java.util.Map<Long, byte[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    @GetMapping("/leaky")
    public String leaky() {
        CACHE.put(SEQ.incrementAndGet(), new byte[8 * 1024]);
        return work();
    }

    /** 给 H2 用的自定义函数：SLEEP_MS(20) 就是“这条 SQL 要跑 20 ms” */
    public static void sleepMs(int ms) throws InterruptedException { Thread.sleep(ms); }
}
