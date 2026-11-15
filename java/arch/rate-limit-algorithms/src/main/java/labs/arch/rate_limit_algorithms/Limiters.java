package labs.arch.rate_limit_algorithms;

/**
 * 四种限流算法的最小实现。时间由调用方传入（毫秒），方便用模拟时钟做可重复的实验。
 * 都不是线程安全的：真实系统里要么加锁 / CAS，要么像 Spring Cloud Gateway 那样放进 Redis Lua 里原子执行。
 */
public final class Limiters {

    private Limiters() {}

    /** 一次请求来了，放不放。 */
    public interface Limiter {
        boolean tryAcquire(long nowMs);
        String name();
    }

    /** 固定窗口计数器：每个整秒一个计数，到 limit 就拒。窗口一换，计数清零。 */
    public static final class FixedWindow implements Limiter {
        private final int limit;
        private final long windowMs;
        private long windowStart = Long.MIN_VALUE;
        private int count;

        public FixedWindow(int limit, long windowMs) { this.limit = limit; this.windowMs = windowMs; }

        public boolean tryAcquire(long now) {
            long w = now - Math.floorMod(now, windowMs);
            if (w != windowStart) { windowStart = w; count = 0; }
            if (count < limit) { count++; return true; }
            return false;
        }
        public String name() { return "固定窗口"; }
    }

    /**
     * 滑动窗口（分桶计数）：把 1 个窗口切成 buckets 个小格，统计“最近 buckets 个小格”的总数。
     * Sentinel 的 LeapArray 就是这个结构（默认 1000 ms 切 2 格）。格越细越准，内存和计算越多。
     */
    public static final class SlidingWindow implements Limiter {
        private final int limit;
        private final long bucketMs;
        private final long[] bucketStart;
        private final int[] counts;

        public SlidingWindow(int limit, long windowMs, int buckets) {
            this.limit = limit;
            this.bucketMs = windowMs / buckets;
            this.bucketStart = new long[buckets];
            this.counts = new int[buckets];
            java.util.Arrays.fill(bucketStart, Long.MIN_VALUE);
        }

        public boolean tryAcquire(long now) {
            int n = counts.length;
            long start = now - Math.floorMod(now, bucketMs);
            int idx = (int) Math.floorMod(start / bucketMs, (long) n);
            if (bucketStart[idx] != start) { bucketStart[idx] = start; counts[idx] = 0; } // 旧格子复用前清零
            long oldest = start - (n - 1) * bucketMs;
            int sum = 0;
            for (int i = 0; i < n; i++) if (bucketStart[i] >= oldest) sum += counts[i];
            if (sum < limit) { counts[idx]++; return true; }
            return false;
        }
        public String name() { return "滑动窗口(" + counts.length + "格)"; }
    }

    /**
     * 令牌桶：桶里最多 capacity 个令牌，每秒补 ratePerSec 个；来一个请求拿一个，拿不到就拒。
     * 不需要后台线程补令牌：每次请求时按“距上次过了多久”一次性补上（懒计算），Guava 和 Gateway 的脚本都这样做。
     */
    public static final class TokenBucket implements Limiter {
        private final double ratePerMs;
        private final double capacity;
        private double tokens;
        private long last;
        private boolean started;

        public TokenBucket(double ratePerSec, double capacity) {
            this.ratePerMs = ratePerSec / 1000.0;
            this.capacity = capacity;
            this.tokens = capacity; // 新桶是满的（Gateway 的脚本也是）
        }

        public boolean tryAcquire(long now) {
            if (started) tokens = Math.min(capacity, tokens + (now - last) * ratePerMs);
            started = true;
            last = now;
            if (tokens >= 1) { tokens -= 1; return true; }
            return false;
        }
        public String name() { return "令牌桶"; }
    }

    /**
     * 按整秒补令牌的令牌桶：照抄 Spring Cloud Gateway request_rate_limiter.lua 的算法，
     * now 取 Redis TIME 的秒数，所以同一秒里不会补令牌。
     */
    public static final class SecondTokenBucket implements Limiter {
        private final long rate;
        private final long capacity;
        private long tokens;
        private long lastSec;

        public SecondTokenBucket(long ratePerSec, long capacity) {
            this.rate = ratePerSec; this.capacity = capacity; this.tokens = capacity; this.lastSec = 0;
        }

        public boolean tryAcquire(long nowMs) {
            long now = Math.floorDiv(nowMs, 1000);
            long delta = Math.max(0, now - lastSec);
            long filled = Math.min(capacity, tokens + delta * rate);
            boolean ok = filled >= 1;
            tokens = ok ? filled - 1 : filled;
            lastSec = now;
            return ok;
        }
        public String name() { return "令牌桶(整秒补)"; }
    }

    /**
     * 漏桶（排队版）：请求进桶排队，出口每 1000/rate ms 放走一个；预计要等超过 maxWaitMs 的直接拒。
     * Sentinel 的“匀速排队”和 Nginx limit_req（不带 nodelay）都是这个意思。返回值是“几点钟放行”，-1 表示拒绝。
     */
    public static final class LeakyQueue {
        private final double intervalMs;
        private final long maxWaitMs;
        private double nextFree = Double.NEGATIVE_INFINITY;

        public LeakyQueue(double ratePerSec, long maxWaitMs) {
            this.intervalMs = 1000.0 / ratePerSec; this.maxWaitMs = maxWaitMs;
        }

        public long offer(long now) {
            double at = Math.max(now, nextFree);
            if (at - now > maxWaitMs) return -1;
            nextFree = at + intervalMs;
            return (long) Math.ceil(at);
        }
    }
}
