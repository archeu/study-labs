package labs.spring.spring_boot4_upgrade;

import jakarta.validation.Valid;
import labs.spring.spring_boot4_upgrade.Model.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class OrderController {
    private final OrderMapper mapper;
    public OrderController(OrderMapper mapper) { this.mapper = mapper; }

    @PostMapping("/orders/{id}/pay")
    public PayResult pay(@PathVariable long id, @Valid @RequestBody PayRequest req, Authentication who) {
        long payNo = 1987654321098765432L;                       // 雪花号，比 2^53 大
        LocalDateTime at = LocalDateTime.of(2026, 11, 12, 20, 0, 1);
        mapper.markPaid(id, Long.parseLong(who.getName()), req.amountCents(), payNo, at);
        return new PayResult(id, PayStatus.PAID, req.channel(), payNo, at);
    }

    /** 第 5 站有两道关：Jackson 读 JSON、Validator 查注解。看是哪一道把请求挡回去的。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        Throwable c = e.getMostSpecificCause();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("gate", "Jackson");
        m.put("cause", c.getClass().getName());
        String msg = String.valueOf(c.getMessage());
        m.put("msg", msg.length() > 160 ? msg.substring(0, 160) : msg);
        return ResponseEntity.badRequest().body(m);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("gate", "Validator");
        var fe = e.getBindingResult().getFieldErrors().get(0);
        m.put("msg", fe.getField() + "=" + fe.getRejectedValue() + "：" + fe.getDefaultMessage());
        return ResponseEntity.badRequest().body(m);
    }
}
