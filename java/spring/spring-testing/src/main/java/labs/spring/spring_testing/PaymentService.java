package labs.spring.spring_testing;

import labs.spring.spring_testing.PaymentGateway.ChannelDeclinedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 第 6～9 站。顺序：查单 → 核对 → 改状态 → 写流水 → 调渠道扣款。
 * 渠道拒绝时，前面写下的“已付款 + 流水”必须一起撤掉。
 * pay() 是对的写法；三个 Mine 方法各埋一颗事务失效的雷。
 */
@Service
public class PaymentService {
    public record PayResult(long orderId, String status, String channelTxn) {}

    public static class AmountMismatchException extends RuntimeException {
        public AmountMismatchException(String msg) { super(msg); }
    }

    private final OrderMapper mapper;
    private final PaymentGateway gateway;

    public PaymentService(OrderMapper mapper, PaymentGateway gateway) {
        this.mapper = mapper;
        this.gateway = gateway;
    }

    /** 对的写法：受检异常也回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public PayResult pay(long orderId, long userId, long amountCents) throws ChannelDeclinedException {
        return doPay(orderId, userId, amountCents);
    }

    /** 雷 1：受检异常。@Transactional 默认只对 RuntimeException 和 Error 回滚。 */
    @Transactional
    public PayResult payCheckedMine(long orderId, long userId, long amountCents) throws ChannelDeclinedException {
        return doPay(orderId, userId, amountCents);
    }

    /** 雷 2：自己 catch 掉。异常没走出方法，事务拦截器以为一切正常，照常提交。 */
    @Transactional(rollbackFor = Exception.class)
    public PayResult paySwallowMine(long orderId, long userId, long amountCents) {
        try {
            return doPay(orderId, userId, amountCents);
        } catch (ChannelDeclinedException e) {
            return new PayResult(orderId, "FAILED", null);
        }
    }

    /** 雷 3：自调用。this.pay() 不经过代理，事务拦截器根本没机会出场。 */
    public PayResult paySelfCallMine(long orderId, long userId, long amountCents) throws ChannelDeclinedException {
        return this.pay(orderId, userId, amountCents);
    }

    private PayResult doPay(long orderId, long userId, long amountCents) throws ChannelDeclinedException {
        var o = mapper.findById(orderId);
        if (o == null || o.userId() != userId) throw new IllegalArgumentException("不是你的订单");
        if (o.amountCents() != amountCents)
            throw new AmountMismatchException("金额不符：应付 " + o.amountCents() + "，实付 " + amountCents);
        if (mapper.changeStatus(orderId, "CREATED", "PAID") != 1)
            throw new IllegalStateException("订单 " + orderId + " 不是待付款");
        String txn = gateway.charge(orderId, amountCents);          // 可能抛 ChannelDeclinedException
        mapper.insertLog(orderId, amountCents, txn);
        return new PayResult(orderId, "PAID", txn);
    }
}
