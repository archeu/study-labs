package labs.java.pano_j01_order_object_life;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final JdbcTemplate jdbc;
    /** 第 4 站：商品本地缓存。启动时一个线程装好，之后只读。 */
    private final Map<Long, Product> catalog = new HashMap<>();
    /** 第 8 站：每个用户的订单。注意：HashMap 不是线程安全的，这里也从不删除，J02、J03 会拿它埋雷。 */
    private final Map<Long, List<Order>> userOrders = new HashMap<>();
    /** 第 9 站：超时取消队列。 */
    private final DelayQueue<CancelTask> cancelQueue = new DelayQueue<>();
    private final AtomicLong ids = new AtomicLong(9526);

    public OrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void load() {
        jdbc.query("SELECT id, name, price FROM product",
                rs -> { catalog.put(rs.getLong(1), new Product(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3))); });
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    CancelTask task = cancelQueue.take();          // 堆顶到期才返回
                    task.order().cancel();
                    log.info("订单 {} 超时未付，已取消", task.order().getId());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "order-canceller");
        t.setDaemon(true);
        t.start();
        log.info("商品缓存装好：{} 个", catalog.size());
    }

    @Transactional
    public Order place(PlaceOrderCmd cmd) {
        Trace.mark(3, "进到 place()，this 是 " + getClass().getSimpleName()
                + "，事务已开启：" + TransactionSynchronizationManager.isActualTransactionActive());

        // 第 4 站：查商品
        List<OrderLine> lines = new ArrayList<>(cmd.items().size());
        for (PlaceOrderCmd.Item it : cmd.items()) {
            Product p = catalog.get(it.skuId());
            if (p == null) throw new IllegalArgumentException("没有商品 " + it.skuId());
            lines.add(new OrderLine(p.id(), p.name(), p.price(), it.qty()));
        }
        Trace.mark(4, bucketInfo(catalog, cmd.items().get(0).skuId()));

        // 第 5 站：new 出订单
        Order order = new Order(ids.incrementAndGet(), cmd.userId(), lines, "来自 TraceFilter 实验");
        Trace.mark(5, "new Order(id=" + order.getId() + ")；" + erasureInfo(lines));

        // 第 6 站：算价
        BigDecimal sum = lines.stream().map(OrderLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Discount d = Discount.parse(cmd.coupon());
        order.setTotal(Discount.apply(d, sum));
        Trace.mark(6, "Stream 求和 " + sum + "，优惠 " + d + " → 应付 " + order.getTotal());

        // 第 7 站：扣库存、落库。条件更新：库存够才扣
        for (OrderLine l : lines) {
            int n = jdbc.update("UPDATE stock SET qty = qty - ? WHERE sku_id = ? AND qty >= ?", l.qty(), l.skuId(), l.qty());
            if (n == 0) {
                Trace.mark(7, "UPDATE stock 影响 0 行 → throw OutOfStockException");
                throw new OutOfStockException(l.skuId(), l.qty());
            }
        }
        jdbc.update("INSERT INTO orders VALUES (?, ?, ?, ?, ?)", order.getId(), order.getUserId(), order.getTotal(),
                order.getStatus().name(), Timestamp.from(order.getCreatedAt()));
        Integer left = jdbc.queryForObject("SELECT qty FROM stock WHERE sku_id = ?", Integer.class, lines.get(0).skuId());
        Trace.mark(7, "扣库存成功，sku " + lines.get(0).skuId() + " 剩 " + left + "；订单行已 INSERT（还没提交）");

        // 第 8 站：放进集合
        userOrders.computeIfAbsent(order.getUserId(), k -> new ArrayList<>()).add(order);
        Trace.mark(8, "lines 是 " + lines.getClass().getSimpleName() + "，userOrders.get(" + order.getUserId()
                + ") 现在有 " + userOrders.get(order.getUserId()).size() + " 单，userOrders 共 " + userOrders.size() + " 个用户");

        // 第 9 站：进延迟队列
        cancelQueue.offer(new CancelTask(order, System.nanoTime() + TimeUnit.MINUTES.toNanos(30)));
        CancelTask head = cancelQueue.peek();
        Trace.mark(9, "DelayQueue 里 " + cancelQueue.size() + " 个任务，堆顶订单 " + head.order().getId()
                + "，还剩 " + head.getDelay(TimeUnit.SECONDS) + " 秒");
        return order;
    }

    Map<String, Object> stats() {
        Runtime rt = Runtime.getRuntime();
        return Map.of("users", userOrders.size(),
                "ordersHeld", userOrders.values().stream().mapToInt(List::size).sum(),
                "cancelQueue", cancelQueue.size(),
                "heapUsedMB", (rt.totalMemory() - rt.freeMemory()) >> 20);
    }

    /** 用反射看 HashMap 内部的桶数组：需要 --add-opens java.base/java.util=ALL-UNNAMED。 */
    static String bucketInfo(Map<Long, Product> map, Long key) {
        int h = key.hashCode();
        int spread = h ^ (h >>> 16);
        try {
            Field table = HashMap.class.getDeclaredField("table");
            table.setAccessible(true);
            Object[] tab = (Object[]) table.get(map);
            int idx = spread & (tab.length - 1);
            Field next = Class.forName("java.util.HashMap$Node").getDeclaredField("next");
            next.setAccessible(true);
            int chain = 0;
            for (Object n = tab[idx]; n != null; n = next.get(n)) chain++;
            return "catalog.get(" + key + "L)：hashCode=" + h + "，扰动后 " + spread + "，" + map.size()
                    + " 个商品，桶数组长 " + tab.length + "，下标 " + idx + "，这个桶里 " + chain + " 个节点（"
                    + tab[idx].getClass().getSimpleName() + "）";
        } catch (InaccessibleObjectException e) {
            return "catalog.get(" + key + "L)：hashCode=" + h + "；反射读 HashMap.table 被模块系统拦下（没加 --add-opens）";
        } catch (ReflectiveOperationException e) {
            return "反射失败：" + e;
        }
    }

    /** 运行时 List<OrderLine> 和 List<String> 是同一个类，但字段上的泛型签名还在。 */
    static String erasureInfo(List<OrderLine> lines) {
        try {
            boolean same = new ArrayList<OrderLine>().getClass() == new ArrayList<String>().getClass();
            String sig = Order.class.getDeclaredField("lines").getGenericType().getTypeName();
            return "ArrayList<OrderLine>.class == ArrayList<String>.class：" + same + "；但字段签名仍是 "
                    + sig.replace("labs.java.pano_j01_order_object_life.", "");
        } catch (NoSuchFieldException e) {
            return e.toString();
        }
    }
}
