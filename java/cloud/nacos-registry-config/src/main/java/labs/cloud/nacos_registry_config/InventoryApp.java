package labs.cloud.nacos_registry_config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 库存服务 inventory-svc：启动时向 Nacos 注册自己，并从配置中心拉 inventory-svc.yaml。
 * 同一个 jar 起两份（端口 18081、18082）就是全景里的 inventory-1、inventory-2。
 */
// 父工程没开 -parameters，所以 @RequestParam 都写上名字
@SpringBootApplication
@EnableConfigurationProperties(InventoryApp.InvProps.class)
@RestController
public class InventoryApp {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApp.class, args);
    }

    /** 方式一：@Value 注入到普通单例里。启动时取一次值，之后不会变。 */
    @Value("${inventory.timeout:2000}")
    private long timeoutByValue;

    /** 方式二：@ConfigurationProperties。配置变了，Spring Cloud 会重新绑定这个 Bean。 */
    @ConfigurationProperties(prefix = "inventory")
    public static class InvProps {
        private long timeout = 2000;
        public long getTimeout() { return timeout; }
        public void setTimeout(long timeout) { this.timeout = timeout; }
    }

    private final InvProps props;
    /** 演练用：让这个实例的业务接口变慢，但进程和 Nacos 连接都正常。 */
    private final AtomicLong slowMs = new AtomicLong();

    public InventoryApp(InvProps props) { this.props = props; }

    @GetMapping("/cfg")
    public Map<String, Object> cfg() {
        return Map.of("byValue", timeoutByValue, "byProperties", props.getTimeout());
    }

    @PostMapping("/stock/reserve")
    public String reserve(@RequestParam("orderId") long orderId, @RequestParam("sku") int sku, @RequestParam("qty") int qty) throws InterruptedException {
        long s = slowMs.get();
        if (s > 0) Thread.sleep(s);
        return "reserved " + qty + " of sku " + sku + " for " + orderId;
    }

    @PostMapping("/chaos/slow")
    public String slow(@RequestParam("ms") long ms) {
        slowMs.set(ms);
        return "slow=" + ms;
    }
}
