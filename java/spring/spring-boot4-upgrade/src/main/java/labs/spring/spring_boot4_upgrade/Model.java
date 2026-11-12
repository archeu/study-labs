package labs.spring.spring_boot4_upgrade;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/** 付款接口的入参、出参和两个枚举。注解只用了 jackson-annotations，这个包在 Jackson 3 里没改名。 */
public final class Model {
    private Model() {}

    /** 渠道。toString 返回中文名，日志里好看：很多项目都这么写。 */
    public enum Channel {
        ALIPAY("支付宝"), WECHAT("微信");
        private final String label;
        Channel(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum PayStatus {
        UNPAID("待支付"), PAID("已支付");
        private final String label;
        PayStatus(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** 第 5 站：Jackson 把请求体读成它，再交给 Validator。 */
    public record PayRequest(@NotNull Channel channel, @Positive long amountCents) {}

    /**
     * 返回值：普通的 getter 类（VO），很多项目还是这么写。
     * payNo 是雪花算法生成的支付流水号，超过 JS 的 2^53，要以字符串输出，前端才不丢精度。
     */
    public static class PayResult {
        private final long orderId;
        private final String status;
        private final PayStatus statusEnum;
        private final Channel channel;
        private final long payNo;
        private final long payNo2;
        private final LocalDateTime paidAt;
        private final String coupon;   // 没用券时是 null

        public PayResult(long orderId, PayStatus st, Channel channel, long payNo, LocalDateTime paidAt) {
            this.orderId = orderId; this.status = st.name(); this.statusEnum = st; this.channel = channel;
            this.payNo = payNo; this.payNo2 = payNo; this.paidAt = paidAt; this.coupon = null;
        }
        public long getOrderId() { return orderId; }
        public String getStatus() { return status; }
        public PayStatus getStatusEnum() { return statusEnum; }
        public Channel getChannel() { return channel; }

        /** 老写法：用 Jackson 2 databind 包里的注解。 */
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        public long getPayNo() { return payNo; }

        /** 两边都认的写法：jackson-annotations 里的 @JsonFormat。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public long getPayNo2() { return payNo2; }

        public LocalDateTime getPaidAt() { return paidAt; }
        public String getCoupon() { return coupon; }
    }
}
