package labs.javase.hashmap_internals;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易靶场第 4、8 站：商品缓存 catalog、用户订单 userOrders，看 HashMap 内部怎么动。
 * JDK 17：java --add-opens java.base/java.util=ALL-UNNAMED -cp target/classes labs.javase.hashmap_internals.Demo
 */
public class Demo {
    record Product(long id, String name, BigDecimal price) {}

    /** hashCode 写死成 42 的 key：所有 key 落进同一个桶。 */
    record BadKey(int id) implements Comparable<BadKey> {
        @Override public int hashCode() { return 42; }
        @Override public int compareTo(BadKey o) { return Integer.compare(id, o.id); }
    }

    /** 同样写死 hashCode，但不实现 Comparable。 */
    record BadKeyNoCmp(int id) {
        @Override public int hashCode() { return 42; }
    }

    /** 两个线程各放 10 万个不同的用户。返回线程里抛出的异常类名（没有就是 null）。 */
    static String race(Map<Long, Long> m, boolean compute) throws InterruptedException {
        String[] err = new String[1];
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            final long base = t * 1_000_000L;
            ts[t] = new Thread(() -> {
                try {
                    for (long u = 0; u < 100_000; u++) {
                        if (compute) m.computeIfAbsent(base + u, k -> 9527L);
                        else m.put(base + u, 9527L);
                    }
                } catch (RuntimeException e) {
                    err[0] = e.getClass().getSimpleName();
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        return err[0];
    }

    static Product p(long id) { return new Product(id, id == 88 ? "机械键盘" : "商品" + id, BigDecimal.valueOf(10 + id)); }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. put 一个个放，看什么时候扩容 ==");
        HashMap<Long, Product> catalog = new HashMap<>();
        System.out.println("  new HashMap<>() 之后：桶数组 " + Peek.capacity(catalog) + "（还没分配），阈值字段 " + Peek.threshold(catalog));
        int lastCap = 0;
        for (long id = 1; id <= 100; id++) {
            catalog.put(id, p(id));
            int cap = Peek.capacity(catalog);
            if (cap != lastCap) {
                System.out.printf("  放第 %3d 个后：桶数组 %3d，阈值 %3d%n", id, cap, Peek.threshold(catalog));
                lastCap = cap;
            }
        }

        System.out.println("\n== 2. 全景第 4 站：catalog.get(88L) ==");
        Long k88 = 88L;
        System.out.printf("  hashCode=%d，扰动后 %d，桶数组 %d，下标 %d，桶里：%s%n",
                k88.hashCode(), Peek.spread(k88), Peek.capacity(catalog), Peek.index(catalog, k88), Peek.bucket(catalog, Peek.index(catalog, k88)));
        catalog.put(344L, p(344));
        System.out.printf("  放进 344：下标 %d（344 = 256 + 88），88 号的桶里：%s%n", Peek.index(catalog, 344L), Peek.bucket(catalog, 88));
        for (long id = 101; id <= 200; id++) catalog.put(id, p(id));
        System.out.printf("  商品加到 %d 个：桶数组 %d，88 的下标 %d（%s），344 的下标 %d（%s）%n", catalog.size(), Peek.capacity(catalog),
                Peek.index(catalog, 88L), Peek.bucket(catalog, Peek.index(catalog, 88L)), Peek.index(catalog, 344L), Peek.bucket(catalog, Peek.index(catalog, 344L)));

        System.out.println("\n== 3. 一个桶越来越长：先扩容，再树化 ==");
        HashMap<BadKey, Integer> bad = new HashMap<>();
        int idx;
        for (int i = 1; i <= 12; i++) {
            bad.put(new BadKey(i), i);
            idx = Peek.index(bad, new BadKey(0));
            System.out.printf("  放第 %2d 个：桶数组 %3d，那个桶 %s%n", i, Peek.capacity(bad), Peek.bucket(bad, idx));
        }

        System.out.println("\n== 4. hashCode 全一样时查 2 万次：Comparable 救了树 ==");
        int n = 20_000;
        for (String kind : List.of("好的 hashCode（Integer）", "全撞 + Comparable", "全撞 + 不可比较")) {
            Map<Object, Integer> m = new HashMap<>();
            Object[] keys = new Object[n];
            for (int i = 0; i < n; i++) {
                keys[i] = kind.startsWith("好") ? Integer.valueOf(i) : kind.contains("Comparable") ? new BadKey(i) : new BadKeyNoCmp(i);
                m.put(keys[i], i);
            }
            long best = Long.MAX_VALUE, sum = 0;
            for (int round = 0; round < 5; round++) {
                long s = System.nanoTime();
                for (Object k : keys) sum += m.get(k);
                best = Math.min(best, System.nanoTime() - s);
            }
            System.out.printf("  %-24s 查 %d 次：%8.2f ms（取 5 轮最快）%s%n", kind, n, best / 1e6, sum == 0 ? "" : "");
        }

        System.out.println("\n== 5. 全景第 8 站：两个线程同时往 userOrders 放 ==");
        for (int run = 1; run <= 3; run++) {
            Map<Long, Long> plain = new HashMap<>();
            race(plain, false);
            int counted = 0;
            for (Long u : plain.keySet()) counted++;
            System.out.printf("  HashMap.put       第 %d 次：应有 200000 个用户，size() = %d，遍历数出 %d%n", run, plain.size(), counted);
        }
        Map<Long, Long> viaCompute = new HashMap<>();
        String err = race(viaCompute, true);
        System.out.println("  HashMap.computeIfAbsent：" + (err == null ? "没报错，size() = " + viaCompute.size() : "线程抛出 " + err + "，size() = " + viaCompute.size()));
        Map<Long, Long> safe = new ConcurrentHashMap<>();
        race(safe, true);
        System.out.println("  ConcurrentHashMap.computeIfAbsent：size() = " + safe.size());

        System.out.println("\n== 6. 可变 key：放进去以后改了字段 ==");
        Map<List<Long>, String> cache = new HashMap<>();
        List<Long> cartKey = new ArrayList<>(List.of(88L));
        cache.put(cartKey, "购物车报价 598.00");
        cartKey.add(66L);                                  // key 的内容变了，hashCode 也变了
        System.out.println("  改了 key 之后 get(同一个对象) → " + cache.get(cartKey) + "；size 还是 " + cache.size());
    }
}
