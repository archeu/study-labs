package labs.juc.virtual_threads_pinning;

import java.util.concurrent.*;
import java.util.Set;

/**
 * 什么时候用：同样 1 万个任务，平台线程池 vs 每任务一个虚拟线程。
 *   java ... Throughput io     每个任务“等下游” 100 ms（sleep），几乎不用 CPU
 *   java ... Throughput cpu    每个任务做固定的一段计算（单独跑约 20 ms），不等任何东西（任务数改成 200）
 */
public class Throughput {
    public static void main(String[] args) throws Exception {
        String kind = args.length > 0 ? args[0] : "io";
        int n = kind.equals("io") ? 10_000 : 200;
        System.out.printf("CPU 核数 %d，任务 %d 个，类型 %s%n", Runtime.getRuntime().availableProcessors(), n, kind);
        boolean vtFirst = args.length > 1 && args[1].equals("vt-first");   // 换个顺序，排除先后的影响
        if (vtFirst) run("虚拟线程 每任务一个", Executors.newVirtualThreadPerTaskExecutor(), n, kind);
        run("平台线程池 200", Executors.newFixedThreadPool(200), n, kind);
        if (!vtFirst) run("虚拟线程 每任务一个", Executors.newVirtualThreadPerTaskExecutor(), n, kind);
    }

    static void run(String label, ExecutorService es, int n, String kind) throws Exception {
        Set<String> osThreads = ConcurrentHashMap.newKeySet();
        long t0 = System.nanoTime();
        try (es) {
            for (int i = 0; i < n; i++) {
                es.submit(() -> {
                    if (kind.equals("io")) Thread.sleep(100); else work(WORK);
                    osThreads.add(osThread());
                    return null;
                });
            }
        } // close() 等全部完成
        System.out.printf("  %-14s %6d ms，真正干活的操作系统线程 %d 个%n", label, (System.nanoTime() - t0) / 1_000_000, osThreads.size());
    }

    /** 平台线程就是它自己；虚拟线程取它当时挂在哪个载体线程上，toString 形如 VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1 */
    static String osThread() {
        Thread t = Thread.currentThread();
        if (!t.isVirtual()) return t.getName();
        String s = t.toString();
        return s.substring(s.indexOf('@') + 1);
    }

    static volatile long sink;
    static final long WORK = calibrate();
    /** 固定的计算量，不看时钟：被抢占了就真的算得慢 */
    static void work(long loops) {
        long x = 1;
        for (long i = 0; i < loops; i++) x = x * 6364136223846793005L + 1442695040888963407L;
        sink += x;
    }
    static long calibrate() {
        long loops = 1_000_000;
        for (int i = 0; i < 5; i++) work(loops);                 // 热身
        long t0 = System.nanoTime(); work(loops); long ns = System.nanoTime() - t0;
        return loops * 20_000_000L / Math.max(1, ns);            // 单独跑约 20 ms 的循环次数
    }
}
