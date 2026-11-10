package labs.juc.completablefuture_orchestration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 全景第 10 站：订单 1024 抢到后发三条通知：短信 300 ms、站内信 100 ms、积分 200 ms。
 * 依次演示：串行 vs allOf 并行、异常被吞、超时兜底、ThreadLocal 带不过去和怎么带。
 */
public class Notify {
    static final ThreadLocal<Long> USER = new ThreadLocal<>();
    static final AtomicInteger seq = new AtomicInteger();
    static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread th = new Thread(r, "notify-" + seq.incrementAndGet()); th.setDaemon(true); return th;
    });
    static long t0;
    static long ms() { return (System.nanoTime() - t0) / 1_000_000; }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    static String send(String what, long cost) { sleep(cost); return what + "✓"; }

    public static void main(String[] args) {
        // 1. 串行
        t0 = System.nanoTime();
        String a = send("短信", 300), b = send("站内信", 100), c = send("积分", 200);
        System.out.printf("1 串行：%s %s %s，%d ms%n", a, b, c, ms());

        // 2. 并行 + allOf 汇总
        t0 = System.nanoTime();
        CompletableFuture<String> sms = CompletableFuture.supplyAsync(() -> send("短信", 300), POOL);
        CompletableFuture<String> inbox = CompletableFuture.supplyAsync(() -> send("站内信", 100), POOL);
        CompletableFuture<String> points = CompletableFuture.supplyAsync(() -> send("积分", 200), POOL);
        String all = CompletableFuture.allOf(sms, inbox, points)
                .thenApply(v -> sms.join() + " " + inbox.join() + " " + points.join()).join();
        System.out.printf("2 并行 allOf：%s，%d ms%n", all, ms());

        // 3. 异常：没人 join，就没人知道
        CompletableFuture.runAsync(() -> { throw new IllegalStateException("短信网关 503"); }, POOL);
        sleep(100);
        System.out.println("3 runAsync 里抛了异常，没人 join：上面什么都没打印");
        String r3 = CompletableFuture.supplyAsync(() -> { if (true) throw new IllegalStateException("短信网关 503"); return "短信✓"; }, POOL)
                .exceptionally(ex -> "短信✗ 记下来补发（" + ex.getClass().getSimpleName() + ": " + ex.getCause().getMessage() + "）")
                .join();
        System.out.println("  加 exceptionally：" + r3);
        try {
            CompletableFuture.supplyAsync(() -> { if (true) throw new IllegalStateException("短信网关 503"); return ""; }, POOL).join();
        } catch (CompletionException e) {
            System.out.println("  join 抛的是 " + e.getClass().getSimpleName() + "，cause 才是 " + e.getCause().getClass().getSimpleName());
        }

        // 4. 超时兜底：短信网关卡住 1500 ms，最多等 500 ms
        t0 = System.nanoTime();
        String r4 = CompletableFuture.supplyAsync(() -> {
                    String s = send("短信", 1500);
                    System.out.printf("  …第 %d ms：卡住的短信任务其实还在跑，现在才结束（%s）%n", ms(), s);
                    return s;
                }, POOL)
                .completeOnTimeout("短信⏳ 超时，进补发队列", 500, TimeUnit.MILLISECONDS)
                .join();
        System.out.printf("4 completeOnTimeout：第 %d ms 拿到 \"%s\"%n", ms(), r4);
        sleep(1200);

        // 5. ThreadLocal 带不过去
        USER.set(1024L);
        String r5 = CompletableFuture.supplyAsync(() -> "异步线程 " + Thread.currentThread().getName() + " 里 USER=" + USER.get(), POOL).join();
        System.out.println("5 主线程 USER=1024，" + r5);
        String r6 = CompletableFuture.supplyAsync(withUser(() -> "异步线程 " + Thread.currentThread().getName() + " 里 USER=" + USER.get()), POOL).join();
        System.out.println("  包一层 withUser：" + r6);
        String r7 = CompletableFuture.supplyAsync(() -> "包装用完后同一个池里 USER=" + USER.get(), POOL).join();
        System.out.println("  " + r7 + "（finally 清掉了，没串号）");
        USER.remove();
    }

    /** 提交时抓取当前线程的 USER，执行前放进去，执行后清掉（TaskDecorator 的思路） */
    static <T> Supplier<T> withUser(Supplier<T> s) {
        Long captured = USER.get();
        return () -> {
            Long old = USER.get();
            USER.set(captured);
            try { return s.get(); } finally { if (old == null) USER.remove(); else USER.set(old); }
        };
    }
}
