package labs.juc.threadpool_tuning;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知池配小了：核心 2、最大 2、队列 1000，压进 400 条通知（每条等下游 20 ms）。
 * 每 100 ms 打一行监控；第 500 ms 时在线把核心和最大线程数都调到 16，看积压怎么消下去。
 * 最后演示优雅关闭：shutdown → awaitTermination → shutdownNow。
 */
public class LiveResize {
    public static void main(String[] args) throws Exception {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1000));
        final int tasks = 400;
        final CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) pool.execute(() -> { sleep(20); done.countDown(); });

        try {
            pool.setCorePoolSize(16);                    // 先调核心、后调最大：JDK 9+ 会抛异常
        } catch (IllegalArgumentException e) {
            System.out.println("先 setCorePoolSize(16)（大于当前最大 2）：抛 IllegalArgumentException");
        }

        System.out.println("  时间ms  线程数  活跃  排队  已完成");
        long t0 = System.nanoTime();
        boolean resized = false;
        while (done.getCount() > 0) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%7d %7d %5d %5d %7d%n", ms, pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size(),
                    pool.getCompletedTaskCount());
            if (!resized && ms >= 500) {
                pool.setMaximumPoolSize(16);             // 正确顺序：先最大、后核心
                pool.setCorePoolSize(16);
                resized = true;
                System.out.printf("   ↑ 第 %d ms：setMaximumPoolSize(16) 再 setCorePoolSize(16)，线程数马上变成 %d%n",
                        ms, pool.getPoolSize());
            }
            Thread.sleep(100);
        }
        System.out.printf("400 条全部发完：%d ms（全程 2 个线程要约 4000 ms）%n", (System.nanoTime() - t0) / 1_000_000);

        System.out.println("\n== 优雅关闭 ==");
        final AtomicInteger finished = new AtomicInteger(), interrupted = new AtomicInteger();
        for (int i = 0; i < 40; i++) pool.execute(() -> {
            try { Thread.sleep(300); finished.incrementAndGet(); } catch (InterruptedException e) { interrupted.incrementAndGet(); }
        });
        pool.shutdown();                                 // 不再收新任务，已收的继续做
        try { pool.execute(() -> { }); } catch (Exception e) { System.out.println("shutdown 之后再提交：" + e.getClass().getSimpleName()); }
        boolean clean = pool.awaitTermination(500, TimeUnit.MILLISECONDS);
        System.out.printf("等 500 ms：%s，已做完 %d 个，队列里还有 %d 个%n", clean ? "全部做完" : "没做完", finished.get(), pool.getQueue().size());
        int dropped = pool.shutdownNow().size();         // 中断正在跑的，返回还没开始的
        pool.awaitTermination(1, TimeUnit.SECONDS);
        System.out.printf("shutdownNow：退回没开始的 %d 个，被中断的 %d 个，做完的共 %d 个%n", dropped, interrupted.get(), finished.get());
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
