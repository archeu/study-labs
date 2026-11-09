package labs.java.pano_j02_order_in_threadpool;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SeckillController {
    private final StockService stock;

    SeckillController(StockService stock) {
        this.stock = stock;
    }

    /** 回包里带上处理它的线程，压测端据此数出 Tomcat 用了多少个线程（第 2 站）。 */
    @PostMapping("/seckill")
    String seckill(@RequestParam(name = "mode", defaultValue = "db") String mode) {
        boolean ok = stock.buy(mode, UserContext.get());
        Thread t = Thread.currentThread();
        return (ok ? "OK " : "SOLD_OUT ") + (t.isVirtual() ? "virtual:" + t : t.getName());
    }
}
