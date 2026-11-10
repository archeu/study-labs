package labs.jvm.jvm_object_new_layout;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/**
 * ⑤ 第二个实验：new 100 万个 Order，线程实际分配了多少字节；
 * 再让 2 个线程一起 new，对比开着 TLAB 和关掉 TLAB（-XX:-UseTLAB）的耗时。
 * 运行：java -Xms1g -Xmx1g -cp target/classes labs.jvm.jvm_object_new_layout.AllocCost [线程数，默认 2]
 *      java -Xms1g -Xmx1g -XX:-UseTLAB -cp target/classes labs.jvm.jvm_object_new_layout.AllocCost
 */
public class AllocCost {
    static final int KEEP = 1_000_000;
    // 存进数组：对象逃逸出方法，JIT 不能把 new 优化掉
    static volatile Object sinkHolder;

    public static void main(String[] args) throws Exception {
        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();

        Order[] keep = new Order[KEEP];
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < KEEP; i++) {
            keep[i] = new Order(i, 1024L, 88, 2, 17600L, "PAID");
        }
        long after = mx.getThreadAllocatedBytes(tid);
        System.out.printf("new %,d 个 Order：线程分配了 %,d 字节，平均每个 %.1f 字节%n",
                KEEP, after - before, (after - before) / (double) KEEP);
        sinkHolder = keep;
        keep = null;
        sinkHolder = null;

        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        long perThread = 50_000_000L;
        for (int round = 1; round <= 3; round++) {
            long ms = race(threads, perThread);
            System.out.printf("第 %d 轮：%d 个线程各 new %,d 个 Order，用时 %,d ms%n", round, threads, perThread, ms);
        }
    }

    static long race(int threads, long perThread) throws InterruptedException {
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                Order[] ring = new Order[1024];
                for (long i = 0; i < perThread; i++) {
                    ring[(int) (i & 1023)] = new Order(i, 1024L, 88, 2, 17600L, "PAID");
                }
                sinkHolder = ring;
            });
        }
        long start = System.nanoTime();
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
