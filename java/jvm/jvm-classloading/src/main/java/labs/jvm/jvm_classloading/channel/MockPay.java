package labs.jvm.jvm_classloading.channel;

import labs.jvm.jvm_classloading.spi.PaymentChannel;

/** 业务方写的实现，只在“应用”加载器里看得见。 */
public class MockPay implements PaymentChannel {
    public String name() { return "MockPay"; }
}
