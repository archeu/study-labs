package labs.juc.jmm_jcstress_cache;

/**
 * 伪共享：两个线程各改各的变量，互不相干，但两个变量挤在同一条 64 字节的缓存行里。
 * 普通 main 程序，不是 JMH，数字看量级。
 */
public class FalseSharing {
    static final long N = 200_000_000L;

    /** a 和 b 相邻，几乎一定在同一条缓存行。 */
    static final class Tight { volatile long a; volatile long b; }

    /** a 和 b 之间塞 7 个 long（56 字节），保证分在两条缓存行。 */
    static final class Padded {
        volatile long a;
        long p1, p2, p3, p4, p5, p6, p7;
        volatile long b;
    }

    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 3; round++) {          // 第 1 轮预热
            long tight = run(new Tight());
            long padded = run(new Padded());
            if (round > 0) System.out.printf("第 %d 轮：挤在一行 %d ms，隔开 %d ms%n", round, tight, padded);
        }
    }

    static long run(final Tight t) throws Exception {
        Thread x = new Thread(() -> { for (long i = 0; i < N; i++) t.a = i; });
        Thread y = new Thread(() -> { for (long i = 0; i < N; i++) t.b = i; });
        return time(x, y);
    }

    static long run(final Padded t) throws Exception {
        Thread x = new Thread(() -> { for (long i = 0; i < N; i++) t.a = i; });
        Thread y = new Thread(() -> { for (long i = 0; i < N; i++) t.b = i; });
        return time(x, y);
    }

    static long time(Thread x, Thread y) throws Exception {
        long t0 = System.nanoTime();
        x.start(); y.start(); x.join(); y.join();
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
