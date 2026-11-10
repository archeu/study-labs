package labs.jvm.jvm_classloading.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** 框架代码：它被“上层”加载器加载，却要找到“下层”加载器里的实现。 */
public class ChannelRegistry {
    /** 只用自己的加载器找：往上看得见，往下看不见。 */
    public static String viaOwnLoader() {
        return list(ServiceLoader.load(PaymentChannel.class, ChannelRegistry.class.getClassLoader()));
    }

    /** ServiceLoader.load(Class) 用的是当前线程的上下文类加载器（TCCL）。 */
    public static String viaTccl() {
        return list(ServiceLoader.load(PaymentChannel.class));
    }

    private static String list(ServiceLoader<PaymentChannel> sl) {
        List<String> names = new ArrayList<String>();
        try {
            for (PaymentChannel c : sl) names.add(c.name() + "(loader=" + c.getClass().getClassLoader() + ")");
        } catch (ServiceConfigurationError e) {
            return "ServiceConfigurationError: " + e.getMessage();
        }
        return names.size() + " 个 " + names;
    }
}
