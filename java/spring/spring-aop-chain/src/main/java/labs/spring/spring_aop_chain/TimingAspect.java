package labs.spring.spring_aop_chain;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 日志耗时切面。顺序从配置 lab.timing-order 读：
 * 不配 = Ordered.LOWEST_PRECEDENCE，和事务的默认顺序打平手；配 0 = 排在事务外面。
 */
@Aspect
@Component
@ConditionalOnProperty(name = "lab.timing", havingValue = "true", matchIfMissing = true)
public class TimingAspect implements Ordered {
    private final int order;

    public TimingAspect(Environment env) {
        this.order = env.getProperty("lab.timing-order", Integer.class, Ordered.LOWEST_PRECEDENCE);
    }

    @Override
    public int getOrder() { return order; }

    @Around("@annotation(labs.spring.spring_aop_chain.Timed)")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        boolean txIn = TransactionSynchronizationManager.isActualTransactionActive();
        long t0 = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long us = (System.nanoTime() - t0) / 1000;
            boolean txOut = TransactionSynchronizationManager.isActualTransactionActive();
            System.out.printf("    [计时] %s %d µs · 进来时有事务=%s · 出去时事务还在=%s → %s%n",
                    pjp.getSignature().getName(), us, txIn, txOut,
                    txIn ? "计时在事务里面，没算提交" : "计时在事务外面，算上了开事务和提交");
        }
    }
}
