package labs.java.pano_j02_order_in_threadpool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Service
class StockService {
    static final long SKU = 88;

    private final JdbcTemplate jdbc;
    /** mem 模式的库存：普通 int 字段，检查和扣减分两步。 */
    private int memLeft;
    /** cas 模式的库存：AtomicInteger，compareAndSet 把“检查 + 扣减”变成一条 CPU 指令。 */
    private final AtomicInteger casLeft = new AtomicInteger();
    final LongAdder casRetries = new LongAdder();
    private final Object lock = new Object();

    /** 第 9 站：每个用户的订单。J01 用的是 HashMap，这里换成 ConcurrentHashMap。 */
    final Map<Long, List<Long>> userOrders = new ConcurrentHashMap<>();
    final AtomicInteger sold = new AtomicInteger();
    private final AtomicLong ids = new AtomicLong(10_000);
    /** 第 10 站：第一次异步通知跑在哪个线程、看不看得到 UserContext。 */
    final AtomicReference<String> asyncSeen = new AtomicReference<>();

    StockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void reset(int qty) {
        jdbc.update("UPDATE stock SET qty = ? WHERE sku_id = ?", qty, SKU);
        jdbc.update("DELETE FROM orders");
        memLeft = qty;
        casLeft.set(qty);
        casRetries.reset();
        sold.set(0);
        userOrders.clear();
        asyncSeen.set(null);
    }

    int memLeft() {
        return memLeft;
    }

    int dbLeft() {
        return jdbc.queryForObject("SELECT qty FROM stock WHERE sku_id = ?", Integer.class, SKU);
    }

    /** 返回 true 表示抢到了。 */
    boolean buy(String mode, long user) {
        boolean ok = switch (mode) {
            case "mem" -> mem();
            case "naive" -> naive();
            case "sync" -> { synchronized (lock) { yield naive(); } }   // 第 6 站：同一个 JVM 里排队
            case "cas" -> cas();
            case "db" -> db();
            default -> throw new IllegalArgumentException(mode);
        };
        if (ok) afterSold(mode, user);
        return ok;
    }

    /** 第 4、5 站（内存版）：读、判断、写回分三步，中间可能被切走。 */
    private boolean mem() {
        int left = memLeft;
        if (left < 1) return false;
        memLeft = left - 1;
        return true;
    }

    /** 第 4、5 站（数据库版）：先 SELECT 再 UPDATE 成算好的值，经典超卖写法。 */
    private boolean naive() {
        int left = dbLeft();
        if (left < 1) return false;
        jdbc.update("UPDATE stock SET qty = ? WHERE sku_id = ?", left - 1, SKU);
        return true;
    }

    /** 第 7 站：CAS 预扣，失败就重读重试。 */
    private boolean cas() {
        while (true) {
            int cur = casLeft.get();
            if (cur < 1) return false;
            if (casLeft.compareAndSet(cur, cur - 1)) break;
            casRetries.increment();
        }
        jdbc.update("UPDATE stock SET qty = qty - 1 WHERE sku_id = ?", SKU);
        return true;
    }

    /** 第 8 站：条件更新，判断和扣减在数据库的一条语句里，行锁保证串行。 */
    private boolean db() {
        return jdbc.update("UPDATE stock SET qty = qty - 1 WHERE sku_id = ? AND qty >= 1", SKU) == 1;
    }

    private void afterSold(String mode, long user) {
        long id = ids.incrementAndGet();
        jdbc.update("INSERT INTO orders VALUES (?, ?, ?, ?)", id, user, SKU, mode);
        sold.incrementAndGet();
        userOrders.computeIfAbsent(user, k -> new CopyOnWriteArrayList<>()).add(id);   // 第 9 站
        CompletableFuture.runAsync(() -> asyncSeen.compareAndSet(null,                // 第 10 站
                Thread.currentThread().getName() + "，UserContext.get() = " + UserContext.get()));
    }
}
