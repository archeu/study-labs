import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class LockBench {
  static long[] proc() throws Exception { long ctx = 0;
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) { for (Path t : ds) { try { for (String l : Files.readAllLines(t.resolve("status"))) if (l.contains("ctxt_switches")) ctx += Long.parseLong(l.replaceAll("\\D", "")); } catch (NoSuchFileException g) { } } }
    String[] st = new String(Files.readAllBytes(Paths.get("/proc/self/stat"))).split(" "); return new long[] {ctx, Long.parseLong(st[13]) + Long.parseLong(st[14])}; }
  interface L { void lock(); void unlock(); }
  static L make(String k) {
    switch (k) {
      case "spin": { final AtomicBoolean b = new AtomicBoolean(); return new L() { public void lock() { while (!b.compareAndSet(false, true)) { } } public void unlock() { b.set(false); } }; }
      case "sync": { Object m = new Object(); return null; }
      case "rl": { final ReentrantLock r = new ReentrantLock(); return new L() { public void lock() { r.lock(); } public void unlock() { r.unlock(); } }; }
      case "fair": { final ReentrantLock r = new ReentrantLock(true); return new L() { public void lock() { r.lock(); } public void unlock() { r.unlock(); } }; }
    }
    throw new IllegalArgumentException(k);
  }
  // 模式 a：8 个线程抢一把锁，锁里 sleep 1 ms（模拟拿着锁做 IO），跑 2 秒：看 CPU 花在哪
  // 模式 b：8 个线程抢一把锁，锁里只做一次自增，跑 2 秒：看吞吐
  public static void main(String[] a) throws Exception {
    final String k = a[0], mode = a[1]; int T = 8;
    final Object mon = new Object(); final L lk = k.equals("sync") ? null : make(k);
    final AtomicBoolean stop = new AtomicBoolean(); final LongAdder ops = new LongAdder(); final long[] counter = {0};
    Thread[] ts = new Thread[T];
    for (int i = 0; i < T; i++) ts[i] = new Thread(() -> {
      while (!stop.get()) {
        if (lk == null) synchronized (mon) { work(mode, counter); } else { lk.lock(); try { work(mode, counter); } finally { lk.unlock(); } }
        ops.increment();
      }
    });
    for (Thread t : ts) t.start();
    Thread.sleep(500);
    long[] b = proc(); long o0 = ops.sum(), t0 = System.nanoTime();
    Thread.sleep(2000);
    long[] e = proc(); long o1 = ops.sum(), t1 = System.nanoTime();
    stop.set(true); for (Thread t : ts) t.join();
    double secs = (t1 - t0) / 1e9;
    System.out.printf("%-5s %s  每秒 %,.0f 次  CPU %.2f 秒（墙钟 %.1f 秒）  切换/次 %.2f%n", k, mode.equals("a") ? "锁里 sleep 1ms" : "锁里只自增  ", (o1 - o0) / secs, (e[1] - b[1]) / 100.0, secs, (double) (e[0] - b[0]) / Math.max(1, o1 - o0));
  }
  static void work(String mode, long[] c) { if (mode.equals("a")) { try { Thread.sleep(1); } catch (InterruptedException x) { } } else c[0]++; }
}
