package labs.spring.spring_boot_bean_ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {
    public record PayRequest(@NotBlank String channel, @Positive long amountCents) {}
    public record PayResult(long orderId, String status) {}

    private final OrderService orderService;
    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping("/orders/{id}/pay")
    public PayResult pay(@PathVariable long id, @Valid @RequestBody PayRequest req, Authentication who) throws Exception {
        Trace.mark("C");
        Trace.ev("⑥ 代理", "Controller 拿到的 orderService 是 " + orderService.getClass().getSimpleName());
        Trace.FACTS.putIfAbsent("controller.thread", Thread.currentThread().getName());
        String st = orderService.pay(id, Long.parseLong(who.getName()), req.amountCents());
        Trace.mark("R");
        return new PayResult(id, st);
    }

    @PostMapping("/orders/{id}/pay-self")
    public PayResult paySelf(@PathVariable long id, @Valid @RequestBody PayRequest req, Authentication who) throws Exception {
        return new PayResult(id, orderService.payViaThis(id, Long.parseLong(who.getName()), req.amountCents()));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public String conflict(IllegalStateException e) { return e.getMessage(); }
}
