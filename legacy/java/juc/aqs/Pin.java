import java.util.concurrent.*;
import java.util.concurrent.locks.*;
// JDK 21：100 个虚拟线程，各拿各的锁，在锁里 sleep 50 ms
public class Pin {
  public static void main(String[] a) throws Exception {
    boolean sync = a[0].equals("synchronized");
    long t0 = System.nanoTime();
    try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++) {
        ex.submit(() -> {
          if (sync) { Object m = new Object(); synchronized (m) { sleep(); } }
          else { var l = new ReentrantLock(); l.lock(); try { sleep(); } finally { l.unlock(); } }
        });
      }
    }
    System.out.printf("%-14s 100 个任务用了 %d ms（载体线程 %d 个）%n", a[0], (System.nanoTime() - t0) / 1_000_000, Runtime.getRuntime().availableProcessors());
  }
  static void sleep() { try { Thread.sleep(50); } catch (InterruptedException e) { } }
}
