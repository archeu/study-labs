package labs.cloud.feign_dubbo_loadbalancer.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.atomic.AtomicLong;

/** 库存服务的替身：注册成 inventory-svc，reserve 返回自己的端口，方便调用方数“落到了哪台”。 */
@SpringBootApplication
@RestController
public class InventoryStub {
    public static void main(String[] args) {
        new SpringApplicationBuilder(InventoryStub.class).properties("spring.config.name=inventory").run(args);
    }

    @Value("${server.port}") int port;
    private final AtomicLong slowMs = new AtomicLong();

    @PostMapping("/stock/reserve")
    public String reserve(@RequestParam("orderId") long orderId, @RequestParam("sku") int sku,
                          @RequestParam("qty") int qty) throws InterruptedException {
        long s = slowMs.get();
        if (s > 0) Thread.sleep(s);
        return String.valueOf(port);
    }

    @PostMapping("/chaos/slow")
    public String slow(@RequestParam("ms") long ms) { slowMs.set(ms); return "slow=" + ms; }
}
