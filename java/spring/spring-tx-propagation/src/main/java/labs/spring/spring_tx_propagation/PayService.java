package labs.spring.spring_tx_propagation;

import java.util.concurrent.CyclicBarrier;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 交易靶场的付款：改订单状态 → 写一条支付流水 → 金额不符就整单回滚。 */
@Service
public class PayService {
    public enum LogMode { REQUIRED, REQUIRES_NEW, NESTED }

    public record OrderPaid(long orderId) {}

    public static class AmountMismatch extends RuntimeException {
        AmountMismatch(String msg) { super(msg); }
    }

    private final TradeMapper mapper;
    private final PayLogService payLog;
    private final DataSource ds;
    private final ApplicationEventPublisher events;

    public PayService(TradeMapper mapper, PayLogService payLog, DataSource ds, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.payLog = payLog;
        this.ds = ds;
        this.events = events;
    }

    /**
     * @param logFails     流水表写失败（模拟）
     * @param catchLogFail 外层把流水的异常 catch 掉，想“流水失败不影响付款”
     */
    @Transactional
    public String pay(long orderId, long amountCents, LogMode mode, boolean logFails, boolean catchLogFail) {
        Lab.log("    付款 " + orderId + " 开始，" + Conn.peek(ds));
        mapper.changeStatus(orderId, "CREATED", "PAID");
        long due = mapper.amountOf(orderId);
        String result = due == amountCents ? "OK" : "AMOUNT_MISMATCH";
        try {
            switch (mode) {
                case REQUIRED -> payLog.recordRequired(orderId, result, logFails);
                case REQUIRES_NEW -> payLog.recordRequiresNew(orderId, result, logFails);
                case NESTED -> payLog.recordNested(orderId, result, logFails);
            }
        } catch (IllegalStateException e) {
            if (!catchLogFail) throw e;
            Lab.log("    外层 catch 住了：" + e.getMessage());
        }
        if (due != amountCents) {
            throw new AmountMismatch("应付 " + due + "，实付 " + amountCents);
        }
        events.publishEvent(new OrderPaid(orderId));
        return "PAID";
    }

    /** 连接池实验：每个线程先占住自己的连接，等齐了再去写流水。 */
    @Transactional
    public String payThenLog(long orderId, LogMode mode, CyclicBarrier allHoldingOne) throws Exception {
        mapper.changeStatus(orderId, "CREATED", "PAID");   // 这时已经占着 1 条连接
        allHoldingOne.await();
        long t0 = System.nanoTime();
        try {
            if (mode == LogMode.REQUIRES_NEW) payLog.recordRequiresNew(orderId, "OK", false);
            else payLog.recordNested(orderId, "OK", false);
        } finally {
            Lab.log("    [" + Thread.currentThread().getName() + "] 写流水用了 " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        return "PAID";
    }

    /** 自锁实验：外层先改订单（拿到行锁），再让 REQUIRES_NEW 的内层改同一行。 */
    @Transactional
    public String payThenTouchOrderInNewTx(long orderId) {
        mapper.changeStatus(orderId, "CREATED", "PAID");
        payLog.touchOrderNew(orderId);
        return "PAID";
    }

    /** 隔离级别实验：外层不写 isolation（DEFAULT，用库自己的），里面两种传播各写 SERIALIZABLE。 */
    @Transactional
    public void isolationPeek() {
        Lab.log("    外层 DEFAULT：" + Conn.peek(ds));
        Lab.log("    内层 REQUIRED + SERIALIZABLE：" + payLog.peekRequiredSerializable());
        Lab.log("    内层 REQUIRES_NEW + SERIALIZABLE：" + payLog.peekNewSerializable());
    }

    /** 超时实验：timeout 只在“下一次要连接 / 发语句”时检查。 */
    @Transactional(timeout = 1)
    public String payWithTimeout(long orderId, boolean sleepBeforeSql) throws InterruptedException {
        if (sleepBeforeSql) Thread.sleep(1500);
        mapper.changeStatus(orderId, "CREATED", "PAID");
        if (!sleepBeforeSql) Thread.sleep(1500);
        return "PAID";
    }
}
