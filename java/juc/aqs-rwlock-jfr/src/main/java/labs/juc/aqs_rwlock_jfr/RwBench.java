package labs.juc.aqs_rwlock_jfr;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * 库存查询读多写少：4 个线程，每次操作 95% 是“查 88 号还剩几件”，5% 是“扣 1 件”。
 * 读的时候顺带算一下页面要显示的东西（几百纳秒的活），让读锁有东西可并行。
 * 四种锁各跑 2 秒，打印读、写吞吐，以及写线程等锁最久的一次。
 */
public class RwBench {
    static final int THREADS = 4;
    static final long RUN_MS = 2000;

    /** 共享的库存：几个字段要一起读才一致（剩余、已售、版本）。 */
    static long left = 1_000_000_000L, sold, version;
    static volatile long sink;
    /** 读的时候算多少轮；带参数可调，比如 2000 模拟“读完还要组装一大段数据”。 */
    static int work = 40;

    interface Guard { long read(); void write(); }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) work = Integer.parseInt(args[0]);
        System.out.println("读一次算 " + work + " 轮；" + "JDK " + System.getProperty("java.version") + "，" + Runtime.getRuntime().availableProcessors()
                + " 核，" + THREADS + " 个线程，读 95% / 写 5%，每种 " + RUN_MS + " ms");
        System.out.println("锁                         读 万次/秒   写 万次/秒   写等锁最久 ms   乐观读失败");
        final ReentrantLock lock = new ReentrantLock();
        run("ReentrantLock", new Guard() {
            public long read() { lock.lock(); try { return view(); } finally { lock.unlock(); } }
            public void write() { lock.lock(); try { take(); } finally { lock.unlock(); } }
        });
        for (final boolean fair : new boolean[]{false, true}) {
            final ReentrantReadWriteLock rw = new ReentrantReadWriteLock(fair);
            run("ReentrantReadWriteLock" + (fair ? "(公平)" : ""), new Guard() {
                public long read() { rw.readLock().lock(); try { return view(); } finally { rw.readLock().unlock(); } }
                public void write() { rw.writeLock().lock(); try { take(); } finally { rw.writeLock().unlock(); } }
            });
        }
        final StampedLock sl = new StampedLock();
        final LongAdder optFail = new LongAdder();
        run("StampedLock 乐观读", new Guard() {
            public long read() {
                long stamp = sl.tryOptimisticRead();     // 不加锁，只拿一个版本戳
                long v = view();
                if (!sl.validate(stamp)) {               // 期间有人写过：退回读锁重来
                    optFail.increment();
                    stamp = sl.readLock();
                    try { v = view(); } finally { sl.unlockRead(stamp); }
                }
                return v;
            }
            public void write() { long s = sl.writeLock(); try { take(); } finally { sl.unlockWrite(s); } }
        });
        System.out.println("（StampedLock 乐观读失败、退回读锁 " + optFail.sum() + " 次）");
    }

    /** 读：把几个字段拼成页面要的数，再做一点计算。 */
    static long view() {
        long a = left, b = sold, c = version, h = 17;
        for (int i = 0; i < work; i++) h = h * 31 + (a ^ b ^ c ^ i);
        return h;
    }

    static void take() { left--; sold++; version++; }

    static void run(String name, final Guard g) throws Exception {
        final AtomicBoolean stop = new AtomicBoolean();
        final LongAdder reads = new LongAdder(), writes = new LongAdder();
        final long[] maxWait = new long[THREADS];
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
                long local = 0;
                while (!stop.get()) {
                    if (r.nextInt(100) < 5) {
                        long t0 = System.nanoTime();
                        g.write();
                        maxWait[id] = Math.max(maxWait[id], System.nanoTime() - t0);
                        writes.increment();
                    } else {
                        local += g.read();
                        reads.increment();
                    }
                }
                sink = local;
            });
            ts.add(th);
            th.start();
        }
        Thread.sleep(RUN_MS);
        stop.set(true);
        for (Thread t : ts) t.join();
        long mw = 0;
        for (long w : maxWait) mw = Math.max(mw, w);
        System.out.printf("%-26s %10.0f %12.1f %14.1f%n", name, reads.sum() / (RUN_MS / 1000.0) / 1e4,
                writes.sum() / (RUN_MS / 1000.0) / 1e4, mw / 1e6);
    }
}
