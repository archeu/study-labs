package labs.juc.thread_state_context_switch;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 交易靶场里的几个线程，各自停在一种状态上。
 * 每个线程打印两种状态：Java 的 Thread.getState()，和 Linux 内核 /proc 里的状态。
 * 带参数 hold：打印完不退出，留 60 秒给你用 jcmd <pid> Thread.print 看。
 */
public class States {
    static final Object stockLock = new Object();       // 扣库存的锁
    static final Object payResult = new Object();       // 等支付回调
    static volatile long sink;

    public static void main(String[] args) throws Exception {
        List<Thread> ts = new ArrayList<Thread>();

        Thread fresh = new Thread(new Runnable() { public void run() {} }, "order-new");     // 只 new，不 start
        ts.add(fresh);

        Thread done = new Thread(new Runnable() { public void run() {} }, "order-done");     // 跑完了
        done.start();
        done.join();
        ts.add(done);

        ts.add(start("price-calc", new Runnable() {             // 一直在算：真的在用 CPU
            public void run() {
                long x = 0;
                while (true) { x += System.nanoTime() % 7; sink = x; }
            }
        }));

        final ServerSocket server = new ServerSocket(0);
        ts.add(start("acceptor", new Runnable() {               // 像 Tomcat 的 Acceptor：等新连接
            public void run() {
                try { server.accept(); } catch (Exception ignored) { }
            }
        }));

        ts.add(start("exec-17", new Runnable() {                // 拿着库存锁，在锁里睡 60 秒
            public void run() {
                synchronized (stockLock) { sleepQuietly(60_000); }
            }
        }));
        Thread.sleep(100);                                      // 让 exec-17 先拿到锁
        ts.add(start("exec-23", new Runnable() {                // 也要这把锁：进不去
            public void run() {
                synchronized (stockLock) { sink++; }
            }
        }));

        ts.add(start("pay-waiter", new Runnable() {             // Object.wait()：等别人 notify
            public void run() {
                synchronized (payResult) {
                    try { payResult.wait(); } catch (InterruptedException ignored) { }
                }
            }
        }));

        final BlockingQueue<String> cancelQueue = new LinkedBlockingQueue<String>();
        ts.add(start("order-canceller", new Runnable() {        // 阻塞队列 take()：底下是 LockSupport.park
            public void run() {
                try { cancelQueue.take(); } catch (InterruptedException ignored) { }
            }
        }));

        Thread.sleep(300);
        Map<String, String> tids = Proc.tidsByName();
        System.out.println("线程名             Java 状态        内核状态  内核线程号");
        for (Thread t : ts) {
            String tid = tids.get(t.getName());
            String k = tid == null ? "-" : Proc.kernelState(tid);
            System.out.printf("%-18s %-16s %-8s %s%n", t.getName(), t.getState(), k, tid == null ? "（内核里没有）" : tid);
        }
        System.out.println("这个进程在内核里一共有 " + Proc.taskCount() + " 个线程（含 JVM 自己的 GC、JIT 等）");

        if (args.length > 0 && args[0].equals("hold")) {
            System.out.println("pid " + pid() + "，60 秒内可以 jcmd <pid> Thread.print");
            Thread.sleep(60_000);
        }
        System.exit(0);
    }

    static Thread start(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** JDK 8 没有 ProcessHandle，从 /proc/self 读。 */
    static String pid() throws Exception {
        return java.nio.file.Files.readSymbolicLink(java.nio.file.Paths.get("/proc/self")).toString();
    }
}
