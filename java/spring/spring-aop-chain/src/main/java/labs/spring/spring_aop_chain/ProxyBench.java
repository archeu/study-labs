package labs.spring.spring_aop_chain;

import java.util.Arrays;

import org.aopalliance.intercept.MethodInterceptor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.framework.ProxyFactory;

/**
 * 粗测：每次调用多穿过一层代理 / 一个拦截器，要多花多少纳秒。
 * 不是 JMH：只做预热 + 5 轮取中位数，看数量级，不看小数点。
 */
public class ProxyBench {

    public static class Calc {
        public int add(int x) { return x + 1; }
    }

    @Aspect
    public static class NoopAspect {
        @Around("execution(* add(..))")
        public Object around(ProceedingJoinPoint pjp) throws Throwable { return pjp.proceed(); }
    }

    interface Call { int add(int x); }

    static final int N = 5_000_000;
    static volatile int sink;

    public static void main(String[] args) {
        Calc target = new Calc();
        MethodInterceptor noop = inv -> inv.proceed();

        report("直接调用目标对象", target::add);
        report("CGLIB 代理，链是空的", proxy(target)::add);
        report("CGLIB 代理 + 1 个拦截器", proxy(target, noop)::add);
        report("CGLIB 代理 + 3 个拦截器", proxy(target, noop, noop, noop)::add);
        report("CGLIB 代理 + 10 个拦截器", proxy(target, noop, noop, noop, noop, noop, noop, noop, noop, noop, noop)::add);
        AspectJProxyFactory aj = new AspectJProxyFactory(target);
        aj.setProxyTargetClass(true);
        aj.addAspect(new NoopAspect());
        Calc ajProxy = aj.getProxy();
        report("CGLIB 代理 + 1 个 @Around 切面", ajProxy::add);
    }

    static Calc proxy(Calc target, MethodInterceptor... interceptors) {
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(true);
        for (MethodInterceptor mi : interceptors) pf.addAdvice(mi);
        return (Calc) pf.getProxy();
    }

    static void report(String name, Call c) {
        for (int i = 0; i < 3; i++) loop(c);            // 预热，让 JIT 编译完
        double[] ns = new double[5];
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            loop(c);
            ns[r] = (System.nanoTime() - t0) / (double) N;
        }
        Arrays.sort(ns);
        System.out.printf("%-26s %7.1f ns/次%n", name, ns[2]);
    }

    static void loop(Call c) {
        int acc = 0;
        for (int i = 0; i < N; i++) acc += c.add(i);
        sink = acc;
    }
}
