package labs.cloud.sentinel_circuit_breaker.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 打开 feign.sentinel.enabled 后，每次调用外面包一层 Sentinel：
 * 资源名 = "POST:http://inventory-svc/stock/reserve"。被熔断或调用出错时走 fallback。
 */
@FeignClient(name = "inventory-svc", fallback = InventoryClient.Fallback.class)
public interface InventoryClient {
    @PostMapping("/stock/reserve")
    String reserve(@RequestParam("orderId") long orderId, @RequestParam("sku") int sku, @RequestParam("qty") int qty);

    /** 降级：不调库存，立刻返回。真实项目里这里返回“人太多，稍后再试”，订单不创建。 */
    @Component
    class Fallback implements InventoryClient {
        @Override public String reserve(long orderId, int sku, int qty) { return "FALLBACK"; }
    }
}
