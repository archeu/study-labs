package labs.juc.synchronized_cas_atomic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一个“扣库存 / 记销量”的计数，五种写法，1 / 2 / 4 个线程，各做 2000 万次。
 * 普通 main，不是 JMH：每种跑 3 轮取最快，只看量级和趋势。
 */
public class Bench {
    static final int OPS = 20_000_000;

    interface Op { void run(); }

    static int plainCount;
    static final Object lock = new Object();
    static final ReentrantLock rlock = new ReentrantLock();
    static final AtomicInteger atomic = new AtomicInteger();
    static final AtomicInteger casCount = new AtomicInteger();
    static final AtomicLong casRetries = new AtomicLong();
    static final LongAdder adder = new LongAdder();

    public static void main(String[] args) throws Exception {
        System.out.println("JDK " + System.getProperty("java.version") + "，" + Runtime.getRuntime().availableProcessors()
                + " 核，UseBiasedLocking=" + biased());
        System.out.println("写法                       1 线程     2 线程     4 线程   （百万次/秒）");
        row("synchronized", () -> { synchronized (lock) { plainCount++; } });
        row("ReentrantLock", () -> { rlock.lock(); try { plainCount++; } finally { rlock.unlock(); } });
        row("AtomicInteger.increment", () -> atomic.incrementAndGet());
        row("手写 CAS 循环", () -> {
            for (;;) {
                int cur = casCount.get();
                if (casCount.compareAndSet(cur, cur + 1)) return;
                casRetries.incrementAndGet();
            }
        });
        row("LongAdder", () -> adder.increment());
        System.out.println("手写 CAS 循环一共失败重试 " + casRetries.get() + " 次（三种线程数、各 3 轮合计）");
    }

    static String biased() {
        for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())
            if (a.contains("UseBiasedLocking")) return a;
        return "默认";
    }

    static void row(String name, Op op) throws Exception {
        StringBuilder sb = new StringBuilder(String.format("%-24s", name));
        for (int threads : new int[]{1, 2, 4}) {
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 3; round++) best = Math.min(best, run(threads, op));
            sb.append(String.format("%10.1f", OPS / (double) best));
        }
        System.out.println(sb);
    }

    /** 返回微秒。 */
    static long run(int threads, final Op op) throws Exception {
        final int per = OPS / threads;
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int k = 0; k < per; k++) op.run();
            });
            t.start();
            ts.add(t);
        }
        long t0 = System.nanoTime();
        go.countDown();
        for (Thread t : ts) t.join();
        return (System.nanoTime() - t0) / 1000;
    }
}
