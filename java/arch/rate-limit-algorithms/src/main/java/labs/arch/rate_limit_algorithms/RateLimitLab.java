package labs.arch.rate_limit_algorithms;

import com.google.common.util.concurrent.RateLimiter;
import labs.arch.rate_limit_algorithms.Limiters.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 同一份流量喂给几种限流器，数每种放进来多少。时间是模拟的（毫秒），所以每次运行结果一样。
 * 规模按全景页缩小 100 倍：限流 100/秒（全景是 1 万/秒），令牌桶容量 200（全景是 2 万）。
 */
public class RateLimitLab {

    public static void main(String[] args) throws Exception {
        System.out.println("== A. 窗口边界：900~1099 ms 每毫秒来 1 个请求（共 200 个），限流 100/秒 ==");
        List<Long> a = new ArrayList<>();
        for (long t = 900; t < 1100; t++) a.add(t);
        for (Limiter l : new Limiter[]{
                new FixedWindow(100, 1000),
                new SlidingWindow(100, 1000, 2),
                new SlidingWindow(100, 1000, 10),
                new TokenBucket(100, 100)}) {
            List<Long> ok = run(l, a);
            System.out.printf("  %-14s 放行 %3d 个，任意 1 秒内最多 %3d 个%n", l.name(), ok.size(), maxIn(ok, 1000));
        }

        System.out.println();
        System.out.println("== A2. 两波：400~499 ms 来 100 个，1000~1099 ms 再来 100 个，限流 100/秒 ==");
        List<Long> a2 = new ArrayList<>();
        for (long t = 400; t < 500; t++) a2.add(t);
        for (long t = 1000; t < 1100; t++) a2.add(t);
        for (Limiter l : new Limiter[]{
                new FixedWindow(100, 1000),
                new SlidingWindow(100, 1000, 2),
                new SlidingWindow(100, 1000, 10),
                new TokenBucket(100, 100)}) {
            List<Long> ok = run(l, a2);
            System.out.printf("  %-14s 放行 %3d 个，任意 1 秒内最多 %3d 个%n", l.name(), ok.size(), maxIn(ok, 1000));
        }

        System.out.println();
        System.out.println("== B. 开抢：0~2999 ms 每毫秒来 1 个请求（1000/秒，是限流值的 10 倍） ==");
        List<Long> b = new ArrayList<>();
        for (long t = 0; t < 3000; t++) b.add(t);
        for (Limiter l : new Limiter[]{
                new FixedWindow(100, 1000),
                new TokenBucket(100, 200),
                new SecondTokenBucket(100, 200)}) {
            List<Long> ok = run(l, b);
            System.out.printf("  %-14s 第 1/2/3 秒放行 %s；第 1 秒每 100 ms：%s%n", l.name(), perSec(ok, 3), per100(ok));
        }
        LeakyQueue q = new LeakyQueue(100, 500);
        List<Long> released = new ArrayList<>();
        long maxWait = 0;
        for (long t : b) {
            long at = q.offer(t);
            if (at >= 0) { released.add(at); maxWait = Math.max(maxWait, at - t); }
        }
        System.out.printf("  %-14s 第 1/2/3 秒放行 %s；第 1 秒每 100 ms：%s；最长排队 %d ms%n",
                "漏桶(排队500ms)", perSec(released, 3), per100(released), maxWait);

        System.out.println();
        System.out.println("== C. Guava RateLimiter.create(100)，真实时钟 ==");
        RateLimiter g = RateLimiter.create(100);
        System.out.printf("  刚创建，连着 tryAcquire 300 次：成功 %d 次%n", tryMany(g, 300));
        TimeUnit.MILLISECONDS.sleep(1500);
        System.out.printf("  空闲 1.5 秒后，再连着 tryAcquire 300 次：成功 %d 次%n", tryMany(g, 300));
        RateLimiter g2 = RateLimiter.create(100);
        long t0 = System.nanoTime();
        double w1 = g2.acquire(50);
        double w2 = g2.acquire(1);
        System.out.printf("  新建后 acquire(50) 等了 %.3f s，紧接着 acquire(1) 等了 %.3f s（总计 %.0f ms）%n",
                w1, w2, (System.nanoTime() - t0) / 1e6);
    }

    static List<Long> run(Limiter l, List<Long> times) {
        List<Long> ok = new ArrayList<>();
        for (long t : times) if (l.tryAcquire(t)) ok.add(t);
        return ok;
    }

    /** 任意长度为 win 的时间段里，最多放行了多少个。 */
    static int maxIn(List<Long> ok, long win) {
        int best = 0, j = 0;
        for (int i = 0; i < ok.size(); i++) {
            while (ok.get(i) - ok.get(j) >= win) j++;
            best = Math.max(best, i - j + 1);
        }
        return best;
    }

    static String perSec(List<Long> ok, int secs) {
        int[] c = new int[secs];
        for (long t : ok) if (t / 1000 < secs) c[(int) (t / 1000)]++;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < secs; i++) sb.append(i == 0 ? "" : "/").append(c[i]);
        return sb.toString();
    }

    static String per100(List<Long> ok) {
        int[] c = new int[10];
        for (long t : ok) if (t < 1000) c[(int) (t / 100)]++;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i == 0 ? "" : " ").append(c[i]);
        return sb.toString();
    }

    static int tryMany(RateLimiter r, int n) {
        int ok = 0;
        for (int i = 0; i < n; i++) if (r.tryAcquire()) ok++;
        return ok;
    }
}
