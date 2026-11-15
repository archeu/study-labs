package labs.cloud.feign_dubbo_loadbalancer.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 只写接口：OpenFeign 启动时给它生成 JDK 动态代理；名字 inventory-svc 交给 LoadBalancer 换成真实地址。 */
@FeignClient(name = "inventory-svc")
public interface InventoryClient {
    @PostMapping("/stock/reserve")
    String reserve(@RequestParam("orderId") long orderId, @RequestParam("sku") int sku, @RequestParam("qty") int qty);
}
