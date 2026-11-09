import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
// 50 个消费者等着，生产者一次放 1 个、放 2000 次：notifyAll 和 Condition.signal 各叫醒多少次
public class Herd {
  static long ctx() throws Exception { long c = 0; try (var ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) { for (Path t : ds) { try { for (String l : Files.readAllLines(t.resolve("status"))) if (l.contains("ctxt_switches")) c += Long.parseLong(l.replaceAll("\\D", "")); } catch (NoSuchFileException g) { } } } return c; }
  public static void main(String[] a) throws Exception {
    boolean useCond = a[0].equals("signal"); int C = 50, N = 2000;
    var q = new ArrayDeque<Integer>(); Object mon = new Object(); var lock = new ReentrantLock(); var notEmpty = lock.newCondition();
    var wakeups = new LongAdder(); var got = new LongAdder();
    Thread[] cs = new Thread[C];
    for (int i = 0; i < C; i++) { cs[i] = new Thread(() -> { try { while (true) {
      if (useCond) { lock.lock(); try { while (q.isEmpty()) { notEmpty.await(); wakeups.increment(); } if (q.poll() < 0) return; } finally { lock.unlock(); } }
      else synchronized (mon) { while (q.isEmpty()) { mon.wait(); wakeups.increment(); } if (q.poll() < 0) return; }
      got.increment(); } } catch (InterruptedException e) { } }); cs[i].start(); }
    Thread.sleep(300); long c0 = ctx();
    for (int i = 0; i < N; i++) {
      if (useCond) { lock.lock(); try { q.add(i); notEmpty.signal(); } finally { lock.unlock(); } }
      else synchronized (mon) { q.add(i); mon.notifyAll(); }
      while (got.sum() <= i) Thread.onSpinWait();                      // 等这一个被取走再放下一个
    }
    long c1 = ctx();
    for (int i = 0; i < C; i++) { if (useCond) { lock.lock(); try { q.add(-1); notEmpty.signal(); } finally { lock.unlock(); } } else synchronized (mon) { q.add(-1); mon.notifyAll(); } }
    for (Thread t : cs) t.join();
    System.out.printf("%-10s 放了 %d 个：等待者醒来 %d 次（平均每个 %.1f 次），线程切换 %d 次%n", useCond ? "signal()" : "notifyAll()", N, wakeups.sum(), wakeups.sum() / (double) N, c1 - c0);
  }
}
