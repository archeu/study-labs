package labs.jvm.jvm_classloading.spi;

/** 框架定义的接口：支付渠道。实现由业务方提供，写在 META-INF/services 里。 */
public interface PaymentChannel {
    String name();
}
