package labs.spring.spring_aop_chain;

import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 付款后发通知：一个方法上同时有 @Async、@Transactional 和自定义切面。 */
@Service
public class NotifyService {
    @Async
    @Transactional
    @Timed
    public CompletableFuture<String> onPaid(long orderId) {
        return CompletableFuture.completedFuture("线程=" + Thread.currentThread().getName()
                + " · 事务已开启=" + TransactionSynchronizationManager.isActualTransactionActive());
    }
}
