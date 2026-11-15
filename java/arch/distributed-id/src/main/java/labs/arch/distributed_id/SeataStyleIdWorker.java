package labs.arch.distributed_id;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 照 Apache Seata 2.6.0 的 org.apache.seata.common.util.IdWorker 写的“改良雪花”，只把取时间换成可注入的时钟，方便做回拨实验。
 * 布局：1 位符号 | 10 位 workerId | 41 位时间戳 | 12 位序列号。
 * 时间戳和序列号合成一个 AtomicLong，启动时用当前时间初始化，之后只做 incrementAndGet，不再看钟；
 * 只有发号速度追上了墙上时钟，才 sleep 5 ms 等一等。
 */
public class SeataStyleIdWorker {
    static final long TWEPOCH = 1588435200000L;           // 2020-05-03，和 Seata 一样
    private static final int WORKER_BITS = 10, TS_BITS = 41, SEQ_BITS = 12;
    private static final long TS_SEQ_MASK = ~(-1L << (TS_BITS + SEQ_BITS));

    private final long workerIdShifted;
    private final AtomicLong timestampAndSequence;
    private final LongSupplier clock;
    /** 因为“发号追上了时钟”而 sleep 的次数 */
    final AtomicLong sleeps = new AtomicLong();

    public SeataStyleIdWorker(long workerId, LongSupplier clock) {
        if (workerId < 0 || workerId > ~(-1 << WORKER_BITS)) throw new IllegalArgumentException("workerId 0..1023");
        this.clock = clock;
        this.timestampAndSequence = new AtomicLong((clock.getAsLong() - TWEPOCH) << SEQ_BITS);
        this.workerIdShifted = workerId << (TS_BITS + SEQ_BITS);
    }

    public long nextId() {
        long current = timestampAndSequence.get() >>> SEQ_BITS;
        if (current >= clock.getAsLong() - TWEPOCH) {      // 逻辑时间跑到了墙上时钟前面（发太快，或者钟往回拨了）
            sleeps.incrementAndGet();
            try { Thread.sleep(5); } catch (InterruptedException ignore) { }
        }
        return workerIdShifted | (timestampAndSequence.incrementAndGet() & TS_SEQ_MASK);
    }
}
