package labs.mybatis.mybatis_result_mapping;

import java.util.List;
import java.util.function.LongConsumer;
import org.apache.ibatis.cursor.Cursor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderMapper m;
    public OrderService(OrderMapper m) { this.m = m; }

    /** 在事务里查订单、再碰每个订单的 items。 */
    @Transactional(readOnly = true)
    public int lazyInTx() {
        int n = 0;
        for (Order o : m.listOrdersLazy()) n += o.getItems().size();
        return n;
    }

    /** 在事务里用 Cursor 一条条读，读到第 probeAt 条时回调一次（用来量堆）。 */
    @Transactional(readOnly = true)
    public long cursorInTx(long probeAt, LongConsumer probe) throws Exception {
        long sum = 0;
        try (Cursor<PayFlow> c = m.cursorFlows()) {
            for (PayFlow f : c) {
                sum += f.getAmount();
                if (c.getCurrentIndex() == probeAt) probe.accept(sum);
            }
        }
        return sum;
    }

    /** 不加 @Transactional：mapper 方法一返回，SqlSession 就关了。 */
    public long cursorNoTx() {
        long sum = 0;
        Cursor<PayFlow> c = m.cursorFlows();
        for (PayFlow f : c) sum += f.getAmount();
        return sum;
    }

    /** 按页导出：每页用完就扔。in 为 true 时整个循环在一个事务里。返回循环结束时（还在事务里）GC 后的堆。 */
    public long pages(java.util.function.LongSupplier heap) {
        for (int off = 0; off < 200_000; off += 20_000) m.listFlowsPage(off, 20_000).size();
        return heap.getAsLong();
    }

    @Transactional(readOnly = true)
    public long pagesInTx(java.util.function.LongSupplier heap) { return pages(heap); }

    public List<Order> lazyNoTx() { return m.listOrdersLazy(); }
}
