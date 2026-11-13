package labs.spring.spring_testing;

/** 外部支付渠道。测试里永远换成 Mockito 的假货：不能真扣钱。 */
public interface PaymentGateway {
    /** 扣款，返回渠道流水号；被拒时抛受检异常。 */
    String charge(long orderId, long amountCents) throws ChannelDeclinedException;

    class ChannelDeclinedException extends Exception {
        public ChannelDeclinedException(String msg) { super(msg); }
    }
}
