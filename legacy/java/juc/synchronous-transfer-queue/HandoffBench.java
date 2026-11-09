import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

// java HandoffBench.java <impl> <pairs> <secs>
//   impl: naive | sq | sqfair | ltqT(transfer/take) | ltqA(put/take，异步)
public class HandoffBench {
  interface H { void put(Object o) throws InterruptedException; Object take() throws InterruptedException; }

  // 朴素做法：一把锁 + 三个条件，put 要等到对方真的取走才返回
  static final class LockedHandoff implements H {
    final ReentrantLock lock = new ReentrantLock();
    final Condition slotFree = lock.newCondition(), hasItem = lock.newCondition(), taken = lock.newCondition();
    Object item; long putSeq, takeSeq;
    public void put(Object e) throws InterruptedException {
      lock.lock();
      try {
        while (item != null) slotFree.await();     // 槽里有别人的，先等
        item = e; long my = ++putSeq; hasItem.signal();
        while (takeSeq < my) taken.await();         // 等对方取走
      } finally { lock.unlock(); }
    }
    public Object take() throws InterruptedException {
      lock.lock();
      try {
        while (item == null) hasItem.await();
        Object e = item; item = null; takeSeq++;
        taken.signalAll(); slotFree.signal();
        return e;
      } finally { lock.unlock(); }
    }
  }

  static H make(String k) {
    switch (k) {
      case "naive": return new LockedHandoff();
      case "sq": case "sqfair": { var q = new SynchronousQueue<Object>(k.equals("sqfair"));
        return new H() { public void put(Object o) throws InterruptedException { q.put(o); } public Object take() throws InterruptedException { return q.take(); } }; }
      case "ltqT": { var q = new LinkedTransferQueue<Object>();
        return new H() { public void put(Object o) throws InterruptedException { q.transfer(o); } public Object take() throws InterruptedException { return q.take(); } }; }
      case "ltqA": { var q = new LinkedTransferQueue<Object>();
        return new H() { public void put(Object o) { q.put(o); } public Object take() throws InterruptedException { return q.take(); } }; }
    }
    throw new IllegalArgumentException(k);
  }

  static long ctx() { // 本进程所有线程的上下文切换次数之和
    long s = 0;
    try (var ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) {
      for (Path t : ds) for (String l : Files.readAllLines(t.resolve("status")))
        if (l.startsWith("voluntary_ctxt_switches") || l.startsWith("nonvoluntary_ctxt_switches")) s += Long.parseLong(l.replaceAll("\\D", ""));
    } catch (Exception e) { }
    return s;
  }

  public static void main(String[] a) throws Exception {
    String k = a[0]; int n = Integer.parseInt(a[1]); double secs = Double.parseDouble(a[2]);
    H h = make(k); Object STOP = new Object(), X = new Object();
    var stop = new AtomicBoolean(); var done = new LongAdder();
    Thread[] ps = new Thread[n], cs = new Thread[n];
    for (int i = 0; i < n; i++) {
      ps[i] = new Thread(() -> { try { while (!stop.get()) h.put(X); } catch (InterruptedException e) { } });
      cs[i] = new Thread(() -> { try { while (h.take() != STOP) done.increment(); } catch (InterruptedException e) { } });
      cs[i].start(); ps[i].start();
    }
    Thread.sleep(1000);
    long c0 = ctx(), d0 = done.sum(), t0 = System.nanoTime();
    Thread.sleep((long) (secs * 1000));
    long c1 = ctx(), d1 = done.sum(), t1 = System.nanoTime();
    stop.set(true);
    for (Thread p : ps) p.join();
    for (int i = 0; i < n; i++) h.put(STOP);
    for (Thread c : cs) c.join();
    double ops = (d1 - d0) / ((t1 - t0) / 1e9);
    System.out.printf("%-6s pairs=%-2d jdk=%-3s ops/s=%-9.0f ctx/transfer=%.2f%n", k, n, Runtime.version().feature(), ops, (double) (c1 - c0) / Math.max(1, d1 - d0));
  }
}
