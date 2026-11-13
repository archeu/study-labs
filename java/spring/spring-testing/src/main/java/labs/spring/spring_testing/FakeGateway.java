package labs.spring.spring_testing;

import org.springframework.stereotype.Component;

/** 本地用的假渠道：总是成功。 */
@Component
public class FakeGateway implements PaymentGateway {
    @Override
    public String charge(long orderId, long amountCents) {
        return "CH-" + orderId;
    }
}
