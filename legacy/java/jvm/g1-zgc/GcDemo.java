import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/** 同一段程序换不同 GC 跑：留住一批“老对象”，不停制造“朝生夕死”的临时对象，
 *  另起一个线程每 1 ms 醒一次，醒晚了多少，就是这段时间被 GC 停住的时间。 */
public class GcDemo {
    static final int LIVE_MB = Integer.getInteger("liveMB", 400);   // 常驻的老对象
    static final int SECONDS = Integer.getInteger("seconds", 20);
    static volatile boolean running = true;
    static volatile Object sink;                                     // 防止临时对象被优化掉

    public static void main(String[] args) throws Exception {
        // 1. 常驻数据：LIVE_MB 个 1 MB，切成 1 KB 的小数组，像一个本地缓存
        final byte[][] cache = new byte[LIVE_MB * 1024][];
        for (int i = 0; i < cache.length; i++) cache[i] = new byte[1024];

        // 2. 探针线程：每 1 ms 醒一次，记下每次醒晚了多少
        final long[] late = new long[SECONDS * 2000];
        final int[] n = {0};
        Thread probe = new Thread(() -> {
            while (running && n[0] < late.length) {
                long t0 = System.nanoTime();
                LockSupport.parkNanos(1_000_000);
                late[n[0]++] = System.nanoTime() - t0 - 1_000_000;
            }
        });
        probe.setDaemon(true);
        probe.start();

        // 3. 业务线程：不停分配临时对象，偶尔替换一条缓存（老对象也会变成垃圾）
        long allocated = 0, end = System.currentTimeMillis() + SECONDS * 1000L;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (System.currentTimeMillis() < end) {
            for (int k = 0; k < 1000; k++) {
                sink = new byte[r.nextInt(64, 512)];                     // 临时对象，马上没用
                allocated += 300;
            }
            cache[r.nextInt(cache.length)] = new byte[1024];             // 老对象换新
            allocated += 1024;
        }
        running = false;
        probe.join();

        long[] a = Arrays.copyOf(late, n[0]);
        Arrays.sort(a);
        System.out.printf("分配了 %.1f GB，探针醒来 %d 次%n", allocated / 1e9, a.length);
        System.out.printf("醒晚了：p50 %.2f ms，p99 %.2f ms，p99.9 %.2f ms，最长 %.1f ms%n",
            a[a.length / 2] / 1e6, a[(int) (a.length * 0.99)] / 1e6,
            a[(int) (a.length * 0.999)] / 1e6, a[a.length - 1] / 1e6);
    }
}
