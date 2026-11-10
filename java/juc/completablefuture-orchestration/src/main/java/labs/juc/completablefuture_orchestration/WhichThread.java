package labs.juc.completablefuture_orchestration;

import java.util.concurrent.*;

/**
 * 回调到底在哪个线程跑？
 *   java ... WhichThread          各种写法各跑一次，打印线程名
 *   java ... WhichThread block    默认池里塞 8 个阻塞 200 ms 的任务，看总耗时
 * 加 -Djava.util.concurrent.ForkJoinPool.common.parallelism=N 对比默认池的变化。
 */
public class WhichThread {
    static String t() { return Thread.currentThread().getName(); }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static void main(String[] args) throws Exception {
        System.out.printf("CPU 核数 %d，commonPool 并行度 %d%n",
                Runtime.getRuntime().availableProcessors(), ForkJoinPool.getCommonPoolParallelism());
        if (args.length > 0 && args[0].equals("block")) { block(); return; }

        CompletableFuture.supplyAsync(() -> { System.out.println("1 supplyAsync 默认池            → " + t()); return 1; }).join();

        CompletableFuture<Integer> slow = CompletableFuture.supplyAsync(() -> { sleep(100); return 2; });
        slow.thenApply(x -> { System.out.println("2 thenApply，前一步还没完成      → " + t() + "（完成前一步的线程）"); return x; }).join();

        CompletableFuture<Integer> done = CompletableFuture.completedFuture(3);
        done.thenApply(x -> { System.out.println("3 thenApply，前一步已经完成      → " + t() + "（调用 thenApply 的线程）"); return x; });

        done.thenApplyAsync(x -> { System.out.println("4 thenApplyAsync                 → " + t()); return x; }).join();

        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "notify-1"));
        done.thenApplyAsync(x -> { System.out.println("5 thenApplyAsync(自己的池)       → " + t()); return x; }, pool).join();
        pool.shutdown();

        CompletableFuture.supplyAsync(() -> { sleep(1000); return 6; })
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> { System.out.println("6 orTimeout 超时后的 exceptionally → " + t() + "（" + ex.getClass().getSimpleName() + "）"); return -1; })
                .join();
    }

    static void block() {
        long t0 = System.nanoTime();
        CompletableFuture<?>[] fs = new CompletableFuture<?>[8];
        java.util.Set<String> names = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < fs.length; i++) {
            fs[i] = CompletableFuture.runAsync(() -> { names.add(t()); sleep(200); });   // 模拟一次 200 ms 的 HTTP 调用
        }
        CompletableFuture.allOf(fs).join();
        System.out.printf("8 个阻塞 200 ms 的任务丢进默认池：%d ms，用了 %d 个线程 %s%n",
                (System.nanoTime() - t0) / 1_000_000, names.size(), names.size() <= 4 ? names : "");
    }
}
