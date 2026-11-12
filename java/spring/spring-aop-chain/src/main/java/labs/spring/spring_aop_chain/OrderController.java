package labs.spring.spring_aop_chain;

import org.springframework.stereotype.Component;

/** 代替全景第 5 站的 Controller：不起 Web，直接调它的方法。 */
@Component
public class OrderController {
    private final OrderService orderService;
    private final NotifyService notifyService;

    public OrderController(OrderService orderService, NotifyService notifyService) {
        this.orderService = orderService;
        this.notifyService = notifyService;
    }

    public String pay(long id) { return orderService.pay(id); }
    public String payTimed(long id) { return orderService.payTimed(id); }
    public String payViaThis(long id) { return orderService.payViaThis(id); }
    public String notifyPaid(long id) throws Exception { return notifyService.onPaid(id).get(); }
}
