package labs.spring.spring_tx_propagation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** 第 10 站：提交以后要做的事。三种写法，看哪种真正落了库。 */
final class NotifyListeners {
    private NotifyListeners() {}

    /** 同步监听，不加 @Transactional：在 afterCommit 里写库。 */
    @Component
    static class Plain {
        private final TradeMapper mapper;
        Plain(TradeMapper mapper) { this.mapper = mapper; }

        @TransactionalEventListener
        public void onPaid(PayService.OrderPaid e) {
            int n = mapper.insertNotify(e.orderId(), "plain");
            Lab.log("    监听器(plain) insert 返回 " + n + "，" + Conn.peek(Lab.ds));
        }
    }

    /** 同步监听 + REQUIRES_NEW：文档推荐的写法。 */
    @Component
    static class RequiresNew {
        private final TradeMapper mapper;
        RequiresNew(TradeMapper mapper) { this.mapper = mapper; }

        @TransactionalEventListener
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void onPaid(PayService.OrderPaid e) {
            int n = mapper.insertNotify(e.orderId(), "requires_new");
            Lab.log("    监听器(requires_new) insert 返回 " + n + "，" + Conn.peek(Lab.ds));
        }
    }

    /** 加普通 @Transactional（REQUIRED）：Spring 6.1 起启动就报错。只在 lab.bad-listener=true 时注册。 */
    @Component
    @ConditionalOnProperty("lab.bad-listener")
    static class Bad {
        @TransactionalEventListener
        @Transactional
        public void onPaid(PayService.OrderPaid e) {}
    }
}
