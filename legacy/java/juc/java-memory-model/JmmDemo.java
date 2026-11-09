import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** 亲眼看到 Java 内存模型要解决的两个问题：看不见、乱了序。JDK 8 / 17 / 21 都能跑。
 *  java JmmDemo stop       不加 volatile 的停止标志
 *  java JmmDemo stopv      加了 volatile
 *  java JmmDemo sb [n]     两个线程各写一个、读另一个，统计四种结果
 *  java JmmDemo sbv [n]    同上，但 x、y 用 volatile 语义读写 */
public class JmmDemo {
    // ---------- 1. 可见性 ----------
    static boolean stop;              // 普通字段
    static volatile boolean vstop;    // volatile 字段

    static void stopDemo(final boolean useVolatile) throws Exception {
        Thread worker = new Thread(() -> {
            long n = 0;
            if (useVolatile) { while (!vstop) n++; }
            else             { while (!stop)  n++; }
            System.out.println("工作线程看到了停止标志，退出（循环了 " + n + " 次）");
        });
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(1000);                          // 让循环先跑一会儿，被 JIT 编译
        if (useVolatile) vstop = true; else stop = true;
        System.out.println("主线程已把标志设为 true");
        worker.join(3000);
        System.out.println(worker.isAlive() ? "3 秒后工作线程还在跑：它一直没看到这次写" : "工作线程已结束");
    }

    // ---------- 2. 重排序：store buffering ----------
    static void sbDemo(final int n, final boolean useVolatile) throws Exception {
        final int[] x = new int[n], y = new int[n], r1 = new int[n], r2 = new int[n];
        final AtomicIntegerArray vx = new AtomicIntegerArray(n), vy = new AtomicIntegerArray(n);
        final AtomicInteger barrier = new AtomicInteger();
        Thread a = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                await(barrier, i);
                if (useVolatile) { vx.set(i, 1); r1[i] = vy.get(i); }   // volatile 写 x，再 volatile 读 y
                else             { x[i] = 1;     r1[i] = y[i]; }        // 普通写 x，再普通读 y
            }
        });
        Thread b = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                await(barrier, i);
                if (useVolatile) { vy.set(i, 1); r2[i] = vx.get(i); }
                else             { y[i] = 1;     r2[i] = x[i]; }
            }
        });
        long t0 = System.nanoTime();
        a.start(); b.start(); a.join(); b.join();     // join 之后，主线程能看到两个线程的全部结果
        int[] c = new int[4];
        for (int i = 0; i < n; i++) c[r1[i] * 2 + r2[i]]++;
        System.out.printf("%d 次（%d ms）：r1=1,r2=1 %d 次 | r1=0,r2=1 %d 次 | r1=1,r2=0 %d 次 | r1=0,r2=0 %d 次%n",
                n, (System.nanoTime() - t0) / 1_000_000, c[3], c[1], c[2], c[0]);
    }
    // 两个线程每一轮都在这里会合，然后几乎同时开始这一轮
    static void await(AtomicInteger barrier, int round) {
        barrier.incrementAndGet();
        while (barrier.get() < 2 * (round + 1)) { /* 自旋等另一个线程 */ }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "stop";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000;
        System.out.println("Java " + System.getProperty("java.version") + "，" + Runtime.getRuntime().availableProcessors() + " 核，模式 " + mode);
        switch (mode) {
            case "stop":  stopDemo(false); break;
            case "stopv": stopDemo(true);  break;
            case "sb":    sbDemo(n, false); break;
            case "sbv":   sbDemo(n, true);  break;
            default: System.out.println("未知模式");
        }
    }
}
