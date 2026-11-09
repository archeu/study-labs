package labs.juc.thread_state_context_switch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 四个小实验，各打印一段：
 * 1. 同样多的计算量，分给 2 / 8 / 64 / 512 个线程：耗时、被抢占次数
 * 2. 每个任务“算 1 ms + 等 20 ms”（像调一次下游接口），线程池开 2 / 8 / 32 / 128 个线程
 * 3. 两个线程来回交接 10 万次（park / unpark）：一次交接多少微秒
 * 4. 新建一个线程 vs 往线程池里扔一个任务：各要多少微秒
 */
public class CtxSwitch {
    static volatile long sink;

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("CPU 核数 " + cores + "，JDK " + System.getProperty("java.version"));
        long unit = calibrate();                       // 1 ms 的计算量大约是多少次循环

        System.out.println("\n== 1. 纯计算：总量相同（约 2 核 × 1 秒），分给不同个数的线程 ==");
        System.out.println("线程数   耗时ms   主动让出   被抢占");
        cpuSplit(2, unit, false);                      // 预热一轮，不打印
        for (int n : new int[]{2, 8, 64, 512}) cpuSplit(n, unit, true);

        System.out.println("\n== 2. 200 个任务，每个算 1 ms、等 20 ms（像调一次下游接口）==");
        System.out.println("线程数   耗时ms   理论下限ms");
        for (int n : new int[]{2, 8, 32, 128}) ioMix(n, unit);

        System.out.println("\n== 3. 两个线程来回交接（park / unpark）==");
        handoff(20_000);                               // 预热
        handoff(100_000);

        System.out.println("\n== 4. 新建线程 vs 线程池 ==");
        create(2_000);                                 // 预热
        create(10_000);
    }

    /** 跑一段纯计算，返回 1 ms 大约多少次循环。 */
    static long calibrate() {
        long n = 1_000_000, t;
        while (true) {
            long t0 = System.nanoTime();
            spin(n);
            t = System.nanoTime() - t0;
            if (t > 50_000_000) break;
            n *= 2;
        }
        return n * 1_000_000 / t;
    }

    static void spin(long n) {
        long x = 0;
        for (long i = 0; i < n; i++) x += i ^ (x >>> 3);
        sink = x;
    }

    static void cpuSplit(final int threads, long unit, boolean print) throws Exception {
        final long per = unit * 2000 / threads;        // 总量 = 2000 ms 的计算
        final AtomicLong vol = new AtomicLong(), nonvol = new AtomicLong();
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        go.await();
                        long[] before = Proc.myCtxSwitches();
                        spin(per);
                        long[] after = Proc.myCtxSwitches();
                        vol.addAndGet(after[0] - before[0]);
                        nonvol.addAndGet(after[1] - before[1]);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            t.start();
            ts.add(t);
        }
        Thread.sleep(100);
        long t0 = System.nanoTime();
        go.countDown();
        for (Thread t : ts) t.join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (print) System.out.printf("%6d %8d %10d %9d%n", threads, ms, vol.get(), nonvol.get());
    }

    static void ioMix(int threads, final long unit) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final int tasks = 200;
        final CountDownLatch done = new CountDownLatch(tasks);
        long t0 = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            pool.execute(new Runnable() {
                public void run() {
                    spin(unit);                        // 算 1 ms
                    LockSupport.parkNanos(20_000_000); // 等 20 ms：线程睡着，不占 CPU
                    done.countDown();
                }
            });
        }
        done.await();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        int cores = Runtime.getRuntime().availableProcessors();
        // 下限：要么受线程数限制（每批 threads 个，每批 21 ms），要么受 CPU 限制（200 ms 计算 / 核数）
        long bound = Math.max((long) Math.ceil(tasks / (double) threads) * 21, 200 / cores + 20);
        System.out.printf("%6d %8d %10d%n", threads, ms, bound);
    }

    static volatile Thread ping, pong;

    static void handoff(final int rounds) throws Exception {
        final AtomicLong turn = new AtomicLong();       // 偶数轮到 ping，奇数轮到 pong
        final long[][] cs = new long[2][];
        ping = new Thread(new Runnable() {
            public void run() {
                try {
                    long[] b = Proc.myCtxSwitches();
                    for (int i = 0; i < rounds; i++) {
                        while (turn.get() % 2 != 0) LockSupport.park();
                        turn.incrementAndGet();
                        LockSupport.unpark(pong);
                    }
                    long[] a = Proc.myCtxSwitches();
                    cs[0] = new long[]{a[0] - b[0], a[1] - b[1]};
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        }, "ping");
        pong = new Thread(new Runnable() {
            public void run() {
                try {
                    long[] b = Proc.myCtxSwitches();
                    for (int i = 0; i < rounds; i++) {
                        while (turn.get() % 2 != 1) LockSupport.park();
                        turn.incrementAndGet();
                        LockSupport.unpark(ping);
                    }
                    long[] a = Proc.myCtxSwitches();
                    cs[1] = new long[]{a[0] - b[0], a[1] - b[1]};
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        }, "pong");
        long t0 = System.nanoTime();
        pong.start();
        ping.start();
        ping.join();
        pong.join();
        long ns = System.nanoTime() - t0;
        if (rounds >= 100_000) {
            System.out.printf("交接 %d 次，共 %d ms，平均一次 %.1f 微秒%n", 2L * rounds, ns / 1_000_000, ns / 1000.0 / (2.0 * rounds));
            System.out.printf("两个线程合计：主动让出 %d 次，被抢占 %d 次%n", cs[0][0] + cs[1][0], cs[0][1] + cs[1][1]);
        }
    }

    static void create(int n) throws Exception {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(new Runnable() { public void run() { sink++; } });
            t.start();
            t.join();
        }
        long perThread = (System.nanoTime() - t0) / n;

        ExecutorService pool = Executors.newFixedThreadPool(1);
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            pool.submit(new Runnable() { public void run() { sink++; } }).get();
        }
        long perTask = (System.nanoTime() - t0) / n;
        pool.shutdown();
        if (n >= 10_000) {
            System.out.printf("新建、启动、等它结束一个线程：平均 %.1f 微秒%n", perThread / 1000.0);
            System.out.printf("同样的活交给已有线程（提交并等结果）：平均 %.1f 微秒%n", perTask / 1000.0);
        }
    }
}
