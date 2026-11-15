package labs.observability.arthas_diagnosis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ReconcileService {

    private final JdbcTemplate jdbc;
    private final PayChannelClient payChannel;

    ReconcileService(JdbcTemplate jdbc, PayChannelClient payChannel) {
        this.jdbc = jdbc;
        this.payChannel = payChannel;
    }

    /**
     * 雷：事务一开始就从池子里拿了连接，一直攥到方法结束；中间那次远程调用有多慢，连接就被占多久。
     * 正确写法见页面 ↺：先查渠道，再开一个短事务改状态。
     */
    @Transactional
    public void reconcileOne(long orderId) {
        jdbc.queryForList("SELECT id, status FROM orders WHERE id = ?", orderId);
        Map<?, ?> r = payChannel.queryOrder(orderId);
        boolean paid = Boolean.TRUE.equals(r.get("paid"));
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", paid ? "PAID" : "UNPAID", orderId);
    }
}
