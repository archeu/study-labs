package labs.juc.threadpool_tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全景第 10 站的通知池过载：核心 2、最大 4、队列 10；一个 Tomcat 线程（main）一口气提交 100 条通知，每条 20 ms。
 * 四种拒绝策略各跑一次：发出去几条、被拒几条、被丢的是哪几条、提交方被拖了多久、通知跑在哪些线程上。
 */
public class Overload {
    public static void main(String[] args) throws Exception {
        System.out.println("策略              发出  被拒/丢  提交 100 条用了  全部发完  丢掉的通知（举例）           谁执行了通知");
        run("AbortPolicy", new ThreadPoolExecutor.AbortPolicy());
        run("CallerRunsPolicy", new ThreadPoolExecutor.CallerRunsPolicy());
        run("DiscardPolicy", new ThreadPoolExecutor.DiscardPolicy());
        run("DiscardOldestPolicy", new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    static void run(String name, RejectedExecutionHandler h) throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(10),
                r -> new Thread(r, "notify-" + COUNTER.incrementAndGet()), h);
        final List<Integer> sent = new CopyOnWriteArrayList<>();
        final java.util.Set<String> runners = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int rejected = 0;
        long t0 = System.nanoTime();
        for (int i = 1; i <= 100; i++) {
            final int id = i;
            try {
                pool.execute(() -> {
                    sleep(20);
                    sent.add(id);
                    String t = Thread.currentThread().getName();
                    runners.add(t.startsWith("notify-") ? "池里线程" : t);
                });
            } catch (RejectedExecutionException e) {
                rejected++;
            }
        }
        long submitMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        long allMs = (System.nanoTime() - t0) / 1_000_000;
        List<Integer> lost = new ArrayList<>();
        for (int i = 1; i <= 100 && lost.size() < 6; i++) if (!sent.contains(i)) lost.add(i);
        System.out.printf("%-18s %4d %8d %13d ms %8d ms  %-28s %s%n", name, sent.size(), 100 - sent.size(), submitMs, allMs,
                lost.isEmpty() ? "-" : lost + (100 - sent.size() > lost.size() ? "…" : ""), runners);
        COUNTER.set(0);
    }

    static final AtomicInteger COUNTER = new AtomicInteger();

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
