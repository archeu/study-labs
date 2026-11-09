package labs.juc.chm_blocking_queue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * J01 全景第 8 站的预言：两个 Tomcat 线程同时往 userOrders（HashMap）里放新用户，会丢。
 * 1. 两个线程各放 50 万个不同的用户，三种 Map 各跑 3 次，看 size 对不对、有没有异常
 * 2. 两个线程各给 10 个商品的销量 +1 共 100 万次：get 再 put，和 merge，看总数对不对
 */
public class MapRace {
    static final int PER_THREAD = 500_000;

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. 两个线程各 put 50 万个不同的 key，应有 1000000 ==");
        for (int round = 1; round <= 3; round++) {
            System.out.printf("第 %d 次  HashMap %-34s synchronizedMap %-10s ConcurrentHashMap %s%n", round,
                    fill(new HashMap<Long, Long>()), fill(Collections.synchronizedMap(new HashMap<Long, Long>())),
                    fill(new ConcurrentHashMap<Long, Long>()));
        }

        System.out.println("\n== 2. 销量计数：两个线程各 +1 共 100 万次，应有 2000000 ==");
        for (int round = 1; round <= 2; round++) {
            final ConcurrentHashMap<Integer, Integer> a = new ConcurrentHashMap<>(), b = new ConcurrentHashMap<>();
            race(() -> { for (int i = 0; i < 1_000_000; i++) { int k = i % 10; Integer v = a.get(k); a.put(k, v == null ? 1 : v + 1); } });
            race(() -> { for (int i = 0; i < 1_000_000; i++) b.merge(i % 10, 1, Integer::sum); });
            System.out.printf("第 %d 次  get 再 put：%d    merge：%d%n", round, sum(a), sum(b));
        }
    }

    static String fill(final Map<Long, Long> m) throws Exception {
        final String[] err = new String[1];
        long t0 = System.nanoTime();
        race2(id -> {
            try {
                for (long i = 0; i < PER_THREAD; i++) { long user = id * 10_000_000L + i; m.put(user, user); }
            } catch (Throwable e) { err[0] = e.getClass().getSimpleName(); }
        });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return m.size() + (err[0] != null ? "（异常 " + err[0] + "）" : "") + "，" + ms + " ms";
    }

    interface IdTask { void run(int id); }

    static void race2(final IdTask t) throws Exception {
        final CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int id = i + 1;
            ts[i] = new Thread(() -> { try { go.await(); } catch (InterruptedException e) { return; } t.run(id); });
            ts[i].start();
        }
        go.countDown();
        for (Thread x : ts) x.join();
    }

    static void race(final Runnable r) throws Exception { race2(id -> r.run()); }

    static int sum(Map<Integer, Integer> m) { int s = 0; for (int v : m.values()) s += v; return s; }
}
