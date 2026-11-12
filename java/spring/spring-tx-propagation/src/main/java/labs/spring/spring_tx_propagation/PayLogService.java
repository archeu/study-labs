package labs.spring.spring_tx_propagation;

import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 写支付流水。单独一个 bean，这样从 PayService 调进来才经过代理（自调用见全景第 6 站）。 */
@Service
public class PayLogService {
    private final TradeMapper mapper;
    private final DataSource ds;

    public PayLogService(TradeMapper mapper, DataSource ds) {
        this.mapper = mapper;
        this.ds = ds;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordRequired(long orderId, String result, boolean fail) { write("REQUIRED", orderId, result, fail); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRequiresNew(long orderId, String result, boolean fail) { write("REQUIRES_NEW", orderId, result, fail); }

    @Transactional(propagation = Propagation.NESTED)
    public void recordNested(long orderId, String result, boolean fail) { write("NESTED", orderId, result, fail); }

    /** 自锁实验：新事务去改外层已经改过（锁住）的同一行订单。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int touchOrderNew(long orderId) { return mapper.touchOrder(orderId); }

    /** 隔离级别实验：加入已有事务时，这里写的 SERIALIZABLE 会被忽略。 */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.SERIALIZABLE)
    public String peekRequiredSerializable() { return Conn.peek(ds); }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public String peekNewSerializable() { return Conn.peek(ds); }

    private void write(String how, long orderId, String result, boolean fail) {
        mapper.insertPayLog(orderId, result);
        Lab.log("    流水(" + how + ") 写入 " + result + "，" + Conn.peek(ds));
        if (fail) {
            throw new IllegalStateException("流水表写失败（模拟）");
        }
    }
}
