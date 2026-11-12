package labs.spring.spring_security_token;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class OrderController {

    /** 订单 9527 属于 1024，9528 属于 1025。第二道门：只能付自己的订单。 */
    @PostMapping("/orders/{id}/pay")
    @PreAuthorize("@orderBook.ownerOf(#id) == authentication.name")
    public Map<String, Object> pay(@PathVariable long id, Authentication auth) {
        Trace.mark("C");
        Trace.ev("Controller 拿到的 Authentication：" + auth.getClass().getSimpleName()
                + " name=" + auth.getName() + " authorities=" + auth.getAuthorities());
        return Map.of("order", id, "status", "PAID", "by", auth.getName());
    }

    /** 同一个用户、同一个权限，写成 hasAuthority('order:pay')：会话里认得，JWT 里认不得。 */
    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("hasAuthority('order:pay')")
    public Map<String, Object> cancel(@PathVariable long id, Authentication auth) {
        return Map.of("order", id, "status", "CANCELLED", "by", auth.getName());
    }

    @Component("orderBook")
    public static class OrderBook {
        private final Map<Long, String> owner = Map.of(9527L, "1024", 9528L, "1025");
        public String ownerOf(long id) { return owner.get(id); }
    }
}
