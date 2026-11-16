package labs.jvm.jvm_startup_aot_cache;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 交易靶场 order-svc 的最小版：只为了被打成镜像。 */
@SpringBootApplication
@RestController
public class OrderApp {

    // 版本号只用来区分
    static final String VERSION = "1.8.3";

    record Order(long id, String status, String servedBy) {}

    @GetMapping("/api/orders/{id}")
    Order get(@PathVariable("id") long id) {
        return new Order(id, "PAID", "order-svc " + VERSION);
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderApp.class, args);
    }
}
