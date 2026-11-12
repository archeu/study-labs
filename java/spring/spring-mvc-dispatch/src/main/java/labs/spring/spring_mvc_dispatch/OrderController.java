package labs.spring.spring_mvc_dispatch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 交易靶场订单服务的 Web 层。业务部分只是一张内存表：这页只看 Web 这几站。 */
@RestController
public class OrderController {
    public record PayRequest(@NotBlank String channel, @Positive long amountCents) {}
    public record PayResult(long orderId, String status) {}

    /** 订单号 → 应付金额（分）。9527 应付 798.00；9529 应付 129.00。 */
    private final Map<Long, Long> due = new ConcurrentHashMap<>(Map.of(9527L, 79800L, 9529L, 12900L, 9530L, 100L));

    @PostMapping("/orders/{id}/pay")
    public PayResult pay(@PathVariable long id, @Valid @RequestBody PayRequest req, Authentication who) {
        Trace.mark("C");
        Trace.EV.add(String.format("%-8s %-22s %s", "", "OrderController#pay", "用户 " + who.getName() + " 付 " + req.amountCents() + " 分"));
        if (id == 9530) throw new NullPointerException("模拟一个没人接的异常");
        Long d = due.get(id);
        if (d == null || d != req.amountCents()) throw new IllegalStateException("金额不符：应付 " + d + "，付了 " + req.amountCents());
        return new PayResult(id, "PAID");
    }

    /** 公开接口：查订单状态，不用登录。id=0 时故意抛异常。 */
    @GetMapping("/public/orders/{id}")
    public PayResult status(@PathVariable long id) {
        Trace.EV.add(String.format("%-8s %-22s %s", "", "OrderController#status", "查订单 " + id));
        if (id == 0) throw new IllegalArgumentException("没有 0 号订单");
        return new PayResult(id, "CREATED");
    }

    /** 业务异常在第一遍分派里就写好响应：不再走 /error。 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
