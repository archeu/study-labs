package labs.java.pano_j05_request_through_spring;

import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OrderService {
    public record OrderPaid(long orderId, long userId) {}

    private final OrderMapper mapper;
    private final DataSource dataSource;
    private final ApplicationEventPublisher events;

    public OrderService(OrderMapper mapper, DataSource dataSource, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.events = events;
    }

    /** 第 7～10 站：进来之前，事务拦截器已经借好连接、关了自动提交。 */
    @Transactional
    public String pay(long orderId, long userId, long amountCents) throws Exception {
        Trace.mark("S");
        boolean tx = TransactionSynchronizationManager.isActualTransactionActive();
        var con = DataSourceUtils.getConnection(dataSource);   // 拿的是绑在当前线程上的那一条
        Trace.ev("⑦ 事务", "进入 pay()：事务已开启=" + tx + "，连接#" + System.identityHashCode(con)
                + " autoCommit=" + con.getAutoCommit() + "，this 的类=" + getClass().getSimpleName());
        Trace.FACTS.putIfAbsent("svc.tx", tx);
        Trace.FACTS.putIfAbsent("svc.con", System.identityHashCode(con));
        DataSourceUtils.releaseConnection(con, dataSource);

        var o = mapper.findById(orderId);
        if (o == null || o.userId() != userId) throw new IllegalArgumentException("不是你的订单");
        int n = mapper.changeStatus(orderId, "CREATED", "PAID");
        if (n != 1) throw new IllegalStateException("订单状态不是 CREATED，影响行数=" + n);
        events.publishEvent(new OrderPaid(orderId, userId));   // 现在只是登记，提交以后才真发
        if (o.amountCents() != amountCents) {
            // 已经 UPDATE 过了，这里抛异常 → 整个事务回滚，监听器也不会被调用
            throw new IllegalStateException("金额不符：应付 " + o.amountCents() + "，实付 " + amountCents);
        }
        Trace.mark("Z");
        return "PAID";
    }

    /** 反例：同一个类里用 this 调 @Transactional 方法，不经过代理，事务不生效。 */
    public String payViaThis(long orderId, long userId, long amountCents) throws Exception {
        return this.pay(orderId, userId, amountCents);
    }
}
