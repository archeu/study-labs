package labs.spring.spring_aop_chain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 交易靶场·订单服务：付款把订单 9527 从 CREATED 改成 PAID。 */
@Service
public class OrderService {
    private final JdbcTemplate jdbc;

    public OrderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 全景第 6、7 站：只有事务一个通知。 */
    @Transactional
    public String pay(long orderId) {
        StackDump.between("OrderController", "OrderService.pay");
        jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", orderId);
        return "事务已开启=" + TransactionSynchronizationManager.isActualTransactionActive();
    }

    /** 同一件事，再多一个自定义切面。 */
    @Transactional
    @Timed
    public String payTimed(long orderId) {
        StackDump.between("OrderController", "OrderService.payTimed");
        jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", orderId);
        return "事务已开启=" + TransactionSynchronizationManager.isActualTransactionActive();
    }

    /** 自调用：从代理进来的是 payViaThis（它没有任何注解），再用 this 调 pay。 */
    public String payViaThis(long orderId) {
        return this.pay(orderId);
    }
}
