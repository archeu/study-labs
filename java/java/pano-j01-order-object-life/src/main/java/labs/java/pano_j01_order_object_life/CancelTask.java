package labs.java.pano_j01_order_object_life;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/** 第 9 站：30 分钟没付款就取消。DelayQueue 内部是一个按到期时间排的二叉堆（PriorityQueue）。 */
record CancelTask(Order order, long deadlineNanos) implements Delayed {
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        return Long.compare(deadlineNanos, ((CancelTask) o).deadlineNanos);
    }
}
