package labs.algo.lru_lfu_limit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第 4 站：每个账户每秒最多 20 单。每个账户一个令牌桶：容量 20，每秒补 20 个。
 * - 懒补充：不用定时任务，账户来请求时按“距上次过了多久”一次补齐
 * - 账户表本身要有上限：几百万账户，大多数一天只下几单；用 LinkedHashMap 的访问顺序当 LRU，把最久没来的账户踢掉
 *   （被踢掉的账户下次来时桶是满的，等于“很久没下单”，这正是被踢的原因，所以不会放错）
 * 时钟由调用方传入（纳秒），便于测试；生产里用 System.nanoTime()，不用会被回拨的 currentTimeMillis()。
 */
public class AccountLimiter {
    static final class Bucket { double tokens; long last; Bucket(double t, long now) { tokens = t; last = now; } }

    private final double capacity, perNano;
    private final Map<Long, Bucket> buckets;

    public AccountLimiter(final int perSecond, final int maxAccounts) {
        this.capacity = perSecond;
        this.perNano = perSecond / 1e9;
        this.buckets = new LinkedHashMap<Long, Bucket>(16, 0.75f, true) {   // true：按访问顺序
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Bucket> e) { return size() > maxAccounts; }
        };
    }

    public synchronized boolean tryAcquire(long account, long nowNanos) {
        Bucket b = buckets.get(account);
        if (b == null) { b = new Bucket(capacity, nowNanos); buckets.put(account, b); }
        b.tokens = Math.min(capacity, b.tokens + (nowNanos - b.last) * perNano);   // 懒补充
        b.last = nowNanos;
        if (b.tokens >= 1) { b.tokens -= 1; return true; }
        return false;
    }

    int size() { return buckets.size(); }

    public static void main(String[] args) {
        AccountLimiter lim = new AccountLimiter(20, 100000);
        long t = 0, ms = 1000000L;
        int ok = 0;
        for (int i = 0; i < 30; i++) if (lim.tryAcquire(1024, t)) ok++;
        System.out.println("账户 1024 在同一毫秒连发 30 单：放行 " + ok + "，拒绝 " + (30 - ok));
        ok = 0;
        for (int i = 0; i < 30; i++) if (lim.tryAcquire(1024, t + 500 * ms)) ok++;
        System.out.println("0.5 秒后再发 30 单：放行 " + ok + "（这 0.5 秒补了 10 个令牌）");
        ok = 0;
        for (int i = 0; i < 100; i++) if (lim.tryAcquire(1024, t + 1000 * ms + i * 50 * ms)) ok++;
        System.out.println("之后每 50 ms 一单、连发 100 单（正好每秒 20 单）：放行 " + ok);
        System.out.println("别的账户不受影响：账户 2048 第一单 " + lim.tryAcquire(2048, t + 600 * ms));

        AccountLimiter small = new AccountLimiter(20, 1000);
        for (long a = 0; a < 1000000; a++) small.tryAcquire(a, a * ms);
        System.out.println("100 万个不同账户各来一次，账户表上限 1000：表里现在 " + small.size() + " 个账户");
    }
}
