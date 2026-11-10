package labs.juc.virtual_threads_pinning;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 钉住：100 个虚拟线程，各自拿一把“自己的”锁（互不竞争），在锁里等 100 ms 的下游。
 *   java ... Pinning sync    synchronized：JDK 21 上睡着也卸不下来，载体线程被钉住
 *   java ... Pinning lock    ReentrantLock：睡着就卸下来
 * 建议加 -Djdk.virtualThreadScheduler.parallelism=2 固定载体线程数；
 * 加 -Djdk.tracePinnedThreads=short 打出钉住的位置。
 */
public class Pinning {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "sync";
        int n = 100;
        long t0 = System.nanoTime();
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                es.submit(() -> {
                    if (mode.equals("sync")) {
                        Object lock = new Object();
                        synchronized (lock) { callDownstream(); }
                    } else {
                        ReentrantLock lock = new ReentrantLock();
                        lock.lock();
                        try { callDownstream(); } finally { lock.unlock(); }
                    }
                    return null;
                });
            }
        }
        System.out.printf("模式 %-5s：100 个虚拟线程、各等 100 ms，用了 %d ms（载体线程 %s 个）%n", mode,
                (System.nanoTime() - t0) / 1_000_000, System.getProperty("jdk.virtualThreadScheduler.parallelism",
                        String.valueOf(Runtime.getRuntime().availableProcessors())));
    }

    static void callDownstream() throws InterruptedException { Thread.sleep(100); }
}
