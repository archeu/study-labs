package labs.juc.chm_blocking_queue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 1. 有界队列满了：put 阻塞、offer 带超时返回 false（背压）
 * 2. DelayQueue：订单 30 分钟（这里缩成 300 ms）不付款就取消，take() 只在到期时返回
 * 3. 两个生产者两个消费者传 400 万个订单号：ArrayBlockingQueue vs LinkedBlockingQueue
 */
public class QueueDemo {
    static final class CancelTask implements Delayed {
        final long orderId, at;
        CancelTask(long orderId, long delayMs) { this.orderId = orderId; this.at = System.nanoTime() + delayMs * 1_000_000; }
        public long getDelay(TimeUnit u) { return u.convert(at - System.nanoTime(), TimeUnit.NANOSECONDS); }
        public int compareTo(Delayed o) { return Long.compare(at, ((CancelTask) o).at); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. ArrayBlockingQueue(3) ==");
        BlockingQueue<Long> q = new ArrayBlockingQueue<>(3);
        for (long id = 10001; id <= 10003; id++) q.put(id);
        long t0 = System.nanoTime();
        boolean ok = q.offer(10004L, 200, TimeUnit.MILLISECONDS);
        System.out.printf("队列满了，offer(10004, 200ms) = %s，等了 %d ms；add 会抛异常，put 会一直等%n",
                ok, (System.nanoTime() - t0) / 1_000_000);

        System.out.println("\n== 2. DelayQueue：到期才能取 ==");
        DelayQueue<CancelTask> dq = new DelayQueue<>();
        t0 = System.nanoTime();
        dq.put(new CancelTask(10017, 300));
        dq.put(new CancelTask(10018, 100));
        dq.put(new CancelTask(10019, 200));
        System.out.println("刚放进去，poll() = " + dq.poll() + "（没到期，取不到）");
        for (int i = 0; i < 3; i++) {
            CancelTask t = dq.take();
            System.out.printf("第 %d 个 take：订单 %d，在第 %d ms%n", i + 1, t.orderId, (System.nanoTime() - t0) / 1_000_000);
        }

        System.out.println("\n== 3. 2 生产 2 消费，传 400 万个订单号 ==");
        for (int round = 0; round < 3; round++) {
            long abq = pipe(new ArrayBlockingQueue<Long>(1024));
            long lbq = pipe(new LinkedBlockingQueue<Long>(1024));
            if (round > 0) System.out.printf("第 %d 轮  ArrayBlockingQueue %d ms   LinkedBlockingQueue %d ms%n", round, abq, lbq);
        }
    }

    static long pipe(final BlockingQueue<Long> q) throws Exception {
        final int perProducer = 2_000_000;
        Thread[] ts = new Thread[4];
        for (int p = 0; p < 2; p++) {
            final long base = p * 10_000_000L;
            ts[p] = new Thread(() -> { try { for (int i = 0; i < perProducer; i++) q.put(base + i); } catch (InterruptedException e) { } });
        }
        for (int c = 2; c < 4; c++) {
            ts[c] = new Thread(() -> { try { for (int i = 0; i < perProducer; i++) q.take(); } catch (InterruptedException e) { } });
        }
        long t0 = System.nanoTime();
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
