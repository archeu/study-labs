package labs.java.pano_j01_order_object_life;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

@RestController
class OrderController {
    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    Order place(@RequestBody PlaceOrderCmd cmd) {
        Trace.mark(2, "Jackson 反序列化出 " + cmd + "，isRecord=" + cmd.getClass().isRecord());
        Trace.mark(3, "注入的 orderService 实际是 " + orderService.getClass().getName()
                .replace("labs.java.pano_j01_order_object_life.", ""));
        Order o = orderService.place(cmd);
        Trace.mark(3, "place() 返回，代理已提交事务");
        return o;
    }

    @GetMapping("/stats")
    Map<String, Object> stats() {
        return orderService.stats();
    }

    @ExceptionHandler(OutOfStockException.class)
    ResponseEntity<Map<String, String>> outOfStock(OutOfStockException e) {
        Trace.mark(7, "异常穿过代理，事务已回滚；@ExceptionHandler 转成 409");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}

/** 第 10 站：Spring 把返回值交给 Jackson 之前，先经过这里。 */
@RestControllerAdvice
class TraceBodyAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter p, Class<? extends HttpMessageConverter<?>> c) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter p, MediaType type,
                                  Class<? extends HttpMessageConverter<?>> c, ServerHttpRequest req, ServerHttpResponse resp) {
        if (body != null) Trace.mark(10, "交给 " + c.getSimpleName() + " 序列化 " + body.getClass().getSimpleName());
        return body;
    }
}
