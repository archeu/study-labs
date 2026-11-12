package labs.java.pano_j05_request_through_spring;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PaidListener {
    private final OrderMapper mapper;
    public PaidListener(OrderMapper mapper) { this.mapper = mapper; }

    /** 第 10 站：事务提交以后，换一个线程去发“付款成功”通知。 */
    @Async
    @TransactionalEventListener   // 默认 phase = AFTER_COMMIT
    public void onPaid(OrderService.OrderPaid e) {
        var o = mapper.findById(e.orderId());
        Trace.ev("⑩ 事件", "收到 OrderPaid(" + e.orderId() + ")，此时库里状态=" + o.status() + " → 发短信给用户 " + e.userId());
        Trace.FACTS.put("listener.thread", Thread.currentThread().getName());
    }
}
