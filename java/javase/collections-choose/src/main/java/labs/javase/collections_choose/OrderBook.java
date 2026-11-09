package labs.javase.collections_choose;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 交易靶场第 8、9 站：一个订单簿，每种需求用一种集合。
 * java -cp target/classes labs.javase.collections_choose.OrderBook
 */
public class OrderBook {

    record OrderLine(long skuId, String name, BigDecimal price, int qty) {}

    record Order(long id, long userId, List<OrderLine> lines, Instant createdAt) {}

    /** 超时任务：按到期时间排序。 */
    record CancelTask(long orderId, Instant deadline) implements Comparable<CancelTask> {
        public int compareTo(CancelTask o) { return deadline.compareTo(o.deadline); }
    }

    // 第 8 站：按用户找订单 → Map；每个用户的订单按下单先后 → List
    private final Map<Long, List<Order>> userOrders = new HashMap<>();
    // 重复提交：同一个请求号只认第一次 → Set
    private final Set<String> seenRequests = new HashSet<>();
    // 第 9 站：最早到期的先处理 → PriorityQueue（DelayQueue 里面就是它）
    private final PriorityQueue<CancelTask> cancelQueue = new PriorityQueue<>();
    // 按时间范围查（对账）：有序 + 范围 → TreeMap
    private final NavigableMap<Instant, List<Order>> byTime = new TreeMap<>();   // 同一时刻可能多单，值用 List
    // 最近 5 单的滑动窗口：两头进出 → ArrayDeque
    private final Deque<Long> recent = new ArrayDeque<>();

    private long nextId = 9527;

    Optional<Order> place(String requestId, long userId, List<OrderLine> lines, Instant now) {
        if (!seenRequests.add(requestId)) return Optional.empty();       // add 返回 false：重复
        List<OrderLine> copy = new ArrayList<>(lines.size());             // 知道大小就预设容量
        copy.addAll(lines);
        Order o = new Order(nextId++, userId, List.copyOf(copy), now);
        userOrders.computeIfAbsent(userId, k -> new ArrayList<>()).add(o);
        cancelQueue.offer(new CancelTask(o.id(), now.plus(Duration.ofMinutes(30))));
        byTime.computeIfAbsent(now, k -> new ArrayList<>()).add(o);
        recent.addLast(o.id());
        if (recent.size() > 5) recent.removeFirst();
        return Optional.of(o);
    }

    List<Long> expire(Instant now) {
        List<Long> cancelled = new ArrayList<>();
        while (!cancelQueue.isEmpty() && !cancelQueue.peek().deadline().isAfter(now)) {
            cancelled.add(cancelQueue.poll().orderId());                  // 只看堆顶，O(log n) 取出
        }
        return cancelled;
    }

    public static void main(String[] args) {
        OrderBook book = new OrderBook();
        Instant t0 = Instant.parse("2026-11-09T12:00:00Z");
        List<OrderLine> kb = List.of(new OrderLine(88, "机械键盘", new BigDecimal("299.00"), 2));

        System.out.println("== 下单 ==");
        System.out.println("  req-1 → " + book.place("req-1", 1024, kb, t0).map(Order::id).orElse(-1L));
        System.out.println("  req-1 再提交一次 → " + book.place("req-1", 1024, kb, t0.plusSeconds(1)).map(o -> "新订单").orElse("重复，忽略"));
        Random r = new Random(7);
        for (int i = 2; i <= 8; i++) {
            long uid = i % 3 == 0 ? 1024 : 2000 + i;
            book.place("req-" + i, uid, kb, t0.plusSeconds(60L * r.nextInt(20)));
        }
        System.out.println("  用户 1024 的订单号：" + book.userOrders.get(1024L).stream().map(Order::id).toList());
        System.out.println("  最近 5 单（ArrayDeque 滑动窗口）：" + book.recent);

        System.out.println("\n== 按时间范围查（TreeMap.subMap）==");
        var window = book.byTime.subMap(t0.plusSeconds(300), true, t0.plusSeconds(900), false);
        window.forEach((t, os) -> System.out.println("  " + t + " → " + os.stream().map(Order::id).toList()));

        System.out.println("\n== 超时取消（PriorityQueue）==");
        System.out.println("  toString 看到的顺序（不是排好序的！）：" + book.cancelQueue.stream().map(CancelTask::orderId).toList());
        System.out.println("  堆顶 peek()：" + book.cancelQueue.peek().orderId() + "，到期 " + book.cancelQueue.peek().deadline());
        System.out.println("  12:40 扫一次，取消：" + book.expire(t0.plus(Duration.ofMinutes(40))));
        System.out.println("  12:50 扫一次，取消：" + book.expire(t0.plus(Duration.ofMinutes(50))));

        System.out.println("\n== 坑 ==");
        List<Order> mine = new ArrayList<>(book.userOrders.get(1024L));
        try {
            for (Order o : mine) if (o.id() == 9527) mine.remove(o);
        } catch (ConcurrentModificationException e) {
            System.out.println("  for-each 里 remove → ConcurrentModificationException");
        }
        mine.removeIf(o -> o.id() == 9527);
        System.out.println("  改用 removeIf → 剩 " + mine.stream().map(Order::id).toList());

        List<String> fixed = Arrays.asList("req-1", "req-2");
        try { fixed.add("req-3"); } catch (UnsupportedOperationException e) { System.out.println("  Arrays.asList(...).add → UnsupportedOperationException（定长视图）"); }
        try { List.of("a", null); } catch (NullPointerException e) { System.out.println("  List.of 放 null → NullPointerException"); }

        Set<OrderLine> byPrice = new TreeSet<>(Comparator.comparing(OrderLine::price));
        byPrice.add(new OrderLine(88, "机械键盘", new BigDecimal("299.00"), 1));
        byPrice.add(new OrderLine(66, "显示器支架", new BigDecimal("299.00"), 1));
        System.out.println("  TreeSet 按价格比较，放两件不同商品 → size = " + byPrice.size() + "（比较结果为 0 就当重复）");
    }
}
