package labs.design.design_patterns_in_frameworks;

import java.lang.reflect.Proxy;

/** 代理：不改 PricingChain 一行，在它前后各加一件事。Spring AOP、MyBatis 的 Mapper 和插件，用的都是这个 JDK 机制。 */
public final class TraceProxy {
    private TraceProxy() {}

    public static Pricing wrap(Pricing target) {
        return (Pricing) Proxy.newProxyInstance(Pricing.class.getClassLoader(), new Class<?>[]{Pricing.class},
            (proxy, method, args) -> {
                System.out.println("  [代理] 调用 " + method.getName() + " 之前：这里可以开事务、计时、查权限");
                Object result = method.invoke(target, args);
                System.out.println("  [代理] 调用 " + method.getName() + " 之后：拿到 " + PatternsWalkthrough.yuan(((Quote) result).payable()));
                return result;
            });
    }
}
