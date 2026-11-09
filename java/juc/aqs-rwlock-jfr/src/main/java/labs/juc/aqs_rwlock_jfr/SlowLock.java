package labs.juc.aqs_rwlock_jfr;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全景第 6 站的坑：锁里调了一次 30 ms 的慢 SQL。8 个线程各下 10 单。
 * 用 JFR 录下来，看能不能不读代码就找到是哪把锁、卡了多久：
 *   java -XX:StartFlightRecording:filename=slow.jfr,settings=profile -cp ... SlowLock
 *   jfr print --events jdk.JavaMonitorEnter,jdk.ThreadPark slow.jfr
 * 第三段：用 Semaphore 把访问“库存服务”的并发限制在 3。
 */
public class SlowLock {
    static final Object stockLock = new Object();
    static final ReentrantLock couponLock = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        runThreads(8, () -> { for (int i = 0; i < 10; i++) deductStock(); }, "order-exec-");
        System.out.printf("synchronized 里慢 SQL：80 单用了 %d ms（串行下限 2400 ms）%n", (System.nanoTime() - t0) / 1_000_000);

        t0 = System.nanoTime();
        runThreads(8, () -> { for (int i = 0; i < 10; i++) useCoupon(); }, "coupon-exec-");
        System.out.printf("ReentrantLock 里慢 SQL：80 单用了 %d ms%n", (System.nanoTime() - t0) / 1_000_000);

        final Semaphore inventoryPermits = new Semaphore(3);
        final AtomicInteger inFlight = new AtomicInteger(), peak = new AtomicInteger(), rejected = new AtomicInteger();
        t0 = System.nanoTime();
        runThreads(8, () -> {
            for (int i = 0; i < 10; i++) {
                try {
                    if (!inventoryPermits.tryAcquire(100, TimeUnit.MILLISECONDS)) { rejected.incrementAndGet(); continue; }
                } catch (InterruptedException e) { return; }
                try {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    slowSql(30);
                    inFlight.decrementAndGet();
                } finally {
                    inventoryPermits.release();
                }
            }
        }, "bulkhead-");
        System.out.printf("Semaphore(3) 限并发：最多同时 %d 个在调库存服务，等 100 ms 拿不到许可被拒绝 %d 次，用了 %d ms%n",
                peak.get(), rejected.get(), (System.nanoTime() - t0) / 1_000_000);
    }

    static void deductStock() {
        synchronized (stockLock) { slowSql(30); }      // 错误示范：锁里做 IO
    }

    static void useCoupon() {
        couponLock.lock();
        try { slowSql(30); } finally { couponLock.unlock(); }
    }

    static void slowSql(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void runThreads(int n, Runnable r, String prefix) throws Exception {
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) { Thread t = new Thread(r, prefix + i); ts.add(t); t.start(); }
        for (Thread t : ts) t.join();
    }
}
