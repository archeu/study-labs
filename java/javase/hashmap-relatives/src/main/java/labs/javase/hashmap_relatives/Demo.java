package labs.javase.hashmap_relatives;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 交易靶场：三个亲戚各干一件事。
 * JDK 17：java --add-opens java.base/java.lang=ALL-UNNAMED -Xmx256m -cp target/classes labs.javase.hashmap_relatives.Demo
 */
public class Demo {
    record Product(long id, String name, BigDecimal price) {}

    /** 订单：普通类，用对象身份判等（没有重写 equals/hashCode）。 */
    static final class Order {
        final long id;
        Order(long id) { this.id = id; }
        public String toString() { return "Order#" + id; }
    }

    /** 1. 最近查过的商品：LinkedHashMap 访问顺序 + removeEldestEntry = LRU。 */
    static final class RecentProducts extends LinkedHashMap<Long, Product> {
        private final int max;
        RecentProducts(int max) {
            super(16, 0.75f, true);            // 第三个参数 accessOrder = true
            this.max = max;
        }
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Product> eldest) {
            boolean evict = size() > max;
            if (evict) System.out.println("      挤掉最久没用的：" + eldest.getKey());
            return evict;
        }
    }

    // 3. 当前请求的用户
    static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    static long usedMb() {
        for (int i = 0; i < 3; i++) { System.gc(); sleep(100); }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static Product p(long id) { return new Product(id, "商品" + id, BigDecimal.valueOf(10 + id)); }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. LinkedHashMap：同样放 88、66、99，再查 88 ==");
        Map<Long, Product> insertion = new LinkedHashMap<>();
        Map<Long, Product> access = new LinkedHashMap<>(16, 0.75f, true);
        Map<Long, Product> plain = new HashMap<>();
        for (Map<Long, Product> m : List.of(insertion, access, plain)) {
            for (long id : new long[]{88, 66, 99}) m.put(id, p(id));
            m.get(88L);
        }
        System.out.println("  HashMap（按桶）       ：" + plain.keySet());
        System.out.println("  插入顺序（默认）      ：" + insertion.keySet());
        System.out.println("  访问顺序（accessOrder）：" + access.keySet() + "  ← 88 被查过，挪到了最后");

        System.out.println("\n== 2. LRU：最多记 3 个最近查过的商品 ==");
        RecentProducts recent = new RecentProducts(3);
        for (long id : new long[]{88, 66, 99, 88, 12, 66, 7}) {
            if (recent.get(id) == null) {
                System.out.println("    查 " + id + "：没命中，放进去");
                recent.put(id, p(id));
            } else {
                System.out.println("    查 " + id + "：命中");
            }
            System.out.println("      现在（旧 → 新）：" + recent.keySet());
        }

        System.out.println("\n== 3. WeakHashMap：给订单挂附加信息，订单没人用了就自动消失 ==");
        Map<Order, String> notes = new WeakHashMap<>();
        List<Order> alive = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            Order o = new Order(9527 + i);
            notes.put(o, "风控评分 " + (i % 100));
            if (i % 2 == 0) alive.add(o);       // 只留一半的强引用
        }
        System.out.println("  放进 10000 个，size() = " + notes.size());
        System.gc(); sleep(200);
        System.out.println("  丢掉一半订单的引用，GC 之后 size() = " + notes.size());

        Map<Order, Order> selfRef = new WeakHashMap<>();
        for (int i = 0; i < 10_000; i++) { Order o = new Order(i); selfRef.put(o, o); }   // value 拉着 key
        System.gc(); sleep(200);
        System.out.println("  坑：value 就是 key 本身，GC 之后 size() = " + selfRef.size() + "（一个都没清掉）");

        Map<String, String> literal = new WeakHashMap<>();
        literal.put("req-1", "字符串字面量做 key");
        literal.put(new String("req-2"), "new 出来的 String 做 key");
        System.gc(); sleep(200);
        System.out.println("  坑：字面量 key 还在吗？" + literal.containsKey("req-1") + "；new String 的还在吗？" + literal.containsKey("req-2"));

        System.out.println("\n== 4. ThreadLocalMap：一个线程里放几个 ThreadLocal，看它们落在哪 ==");
        List<ThreadLocal<String>> tls = new ArrayList<>();
        for (int i = 0; i < 6; i++) { ThreadLocal<String> t = new ThreadLocal<>(); t.set("v" + i); tls.add(t); }
        CURRENT_USER.set(1024L);
        Field tlField = Thread.class.getDeclaredField("threadLocals");
        tlField.setAccessible(true);
        Object map = tlField.get(Thread.currentThread());
        Field tableField = map.getClass().getDeclaredField("table");
        tableField.setAccessible(true);
        Object table = tableField.get(map);
        Field hc = ThreadLocal.class.getDeclaredField("threadLocalHashCode");
        hc.setAccessible(true);
        int len = Array.getLength(table);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            Object e = Array.get(table, i);
            if (e == null) { sb.append(" _"); continue; }
            ThreadLocal<?> k = (ThreadLocal<?>) ((java.lang.ref.Reference<?>) e).get();
            int home = (int) hc.get(k) & (len - 1);
            sb.append(' ').append(i).append(home == i ? "" : "(本该在" + home + ")");
        }
        System.out.println("  table 长 " + len + "，有东西的格子：" + sb.toString().trim());
        for (ThreadLocal<String> t : tls) t.remove();
        CURRENT_USER.remove();

        System.out.println("\n== 5. 线程池里忘了 remove：下一个请求看到了上一个用户 ==");
        ExecutorService pool = Executors.newFixedThreadPool(1);   // 1 个线程，方便看清是同一个
        pool.submit(() -> { CURRENT_USER.set(1024L); System.out.println("  请求 A（用户 1024）在 " + Thread.currentThread().getName() + " 上，处理完没 remove"); }).get();
        pool.submit(() -> System.out.println("  请求 B（匿名用户）在 " + Thread.currentThread().getName() + " 上，读到当前用户 = " + CURRENT_USER.get())).get();
        pool.submit(() -> CURRENT_USER.remove()).get();
        pool.submit(() -> {
            try { CURRENT_USER.set(2048L); System.out.println("  请求 C 用 try/finally，处理用户 " + CURRENT_USER.get()); }
            finally { CURRENT_USER.remove(); }
        }).get();
        pool.submit(() -> System.out.println("  请求 D 读到当前用户 = " + CURRENT_USER.get())).get();

        System.out.println("\n== 6. 线程池里忘了 remove：大对象一直挂在线程上 ==");
        ExecutorService pool4 = Executors.newFixedThreadPool(4);
        long before = usedMb();
        ThreadLocal<byte[]> buffer = new ThreadLocal<>();
        for (int i = 0; i < 4; i++) pool4.submit(() -> buffer.set(new byte[20 << 20])).get();   // 每个线程 20 MB
        sleep(100);
        long after = usedMb();
        System.out.printf("  4 个线程各放一个 20 MB，任务都结束了，GC 后多占 %d MB%n", after - before);
        buffer.set(null);
        for (int i = 0; i < 8; i++) pool4.submit(() -> buffer.remove()).get();
        System.out.printf("  在每个线程上 remove 之后，多占 %d MB（线程池的任务分配不保证每个线程都轮到，以实际为准）%n", usedMb() - before);
        pool.shutdown(); pool4.shutdown();
        pool4.awaitTermination(5, TimeUnit.SECONDS);
        System.out.printf("  线程池关掉、线程结束后，多占 %d MB%n", usedMb() - before);
    }
}
