package labs.spring.spring_testing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import labs.spring.spring_testing.PaymentGateway.ChannelDeclinedException;
import labs.spring.spring_testing.PaymentService.AmountMismatchException;
import labs.spring.spring_testing.PaymentService.PayResult;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 第 4、5、11 站：路由、参数解析和校验、返回值转 JSON、异常转状态码。 */
@RestController
public class OrderController {
    public record PayRequest(@NotBlank String channel, @Positive long amountCents) {}

    private final PaymentService payments;
    public OrderController(PaymentService payments) { this.payments = payments; }

    @PostMapping("/orders/{id}/pay")
    public PayResult pay(@PathVariable long id, @Valid @RequestBody PayRequest req, Authentication who)
            throws ChannelDeclinedException {
        return payments.pay(id, Long.parseLong(who.getName()), req.amountCents());
    }

    @ExceptionHandler(AmountMismatchException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String mismatch(AmountMismatchException e) { return e.getMessage(); }

    @ExceptionHandler(ChannelDeclinedException.class)
    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    public String declined(ChannelDeclinedException e) { return e.getMessage(); }
}
