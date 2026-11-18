package labs.algo.stack_queue_ring;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 队列里常驻 n 张单，反复“队尾进一张、队头出一张”，量每对操作的耗时和新分配的内存。
 * 粗测：直接起 JVM 跑，别在 Maven 进程里跑；看比值，不看绝对值。
 *
 * java -Xms1g -Xmx1g -cp algo/stack-queue-ring/target/classes labs.algo.stack_queue_ring.QueueBench
 */
public class QueueBench {
    static final int POOL = 1 << 20;
    static final Long[] IDS = new Long[POOL];          // 订单号对象先建好，计时里不装箱
    static { for (int i = 0; i < POOL; i++) IDS[i] = (long) i; }

    static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static long sink;

    interface Q { void offer(int i); void poll(); }

    static Q wrap(final Queue<Long> q) {
        return new Q() {
            public void offer(int i) { q.offer(IDS[i & (POOL - 1)]); }
            public void poll() { sink += q.poll(); }
        };
    }

    static Q arrayListQ() {
        final ArrayList<Long> l = new ArrayList<>();
        return new Q() {
            public void offer(int i) { l.add(IDS[i & (POOL - 1)]); }
            public void poll() { sink += l.remove(0); }      // 老办法：每次把后面的整体前挪
        };
    }

    static Q ringQ(int n) {
        final OrderRing r = new OrderRing(Integer.highestOneBit(n) << 1);
        return new Q() {
            public void offer(int i) { r.offer(i); }
            public void poll() { sink += r.poll(); }
        };
    }

    /** 返回 {每对操作纳秒, 每对操作新分配字节} */
    static double[] run(Q q, int n, int ops) {
        for (int i = 0; i < n; i++) q.offer(i);
        int i = n;
        for (int k = 0; k < Math.min(ops, 20_000); k++) { q.offer(i++); q.poll(); }  // 预热
        long tid = Thread.currentThread().getId();
        long b0 = MX.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        for (int k = 0; k < ops; k++) { q.offer(i++); q.poll(); }
        long t1 = System.nanoTime();
        long b1 = MX.getThreadAllocatedBytes(tid);
        return new double[]{(t1 - t0) / (double) ops, (b1 - b0) / (double) ops};
    }

    public static void main(String[] args) {
        int[] sizes = {1_000, 10_000, 100_000};
        System.out.println("队列里常驻 n 张单，每行 = 进一张 + 出一张，平均耗时 / 新分配字节");
        System.out.printf("%-14s %18s %18s %18s%n", "", "n=1,000", "n=10,000", "n=100,000");
        String[] names = {"ArrayList", "LinkedList", "ArrayDeque", "OrderRing"};
        for (String name : names) {
            StringBuilder row = new StringBuilder(String.format("%-14s", name));
            for (int n : sizes) {
                Q q;
                int ops = 2_000_000;
                switch (name) {
                    case "ArrayList": q = arrayListQ(); ops = n >= 100_000 ? 20_000 : 200_000; break;
                    case "LinkedList": q = wrap(new LinkedList<Long>()); break;
                    case "ArrayDeque": q = wrap(new ArrayDeque<Long>()); break;
                    default: q = ringQ(n);
                }
                double[] r = run(q, n, ops);
                row.append(String.format(" %9.1f ns %4.0f B", r[0], r[1]));
            }
            System.out.println(row);
        }
        if (sink == 42) System.out.println();
    }
}
