package lab;

import com.zaxxer.hikari.util.ConcurrentBag;
import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

// java lab.Bench <pool> <threads> <poolSize> <hold> <secs> [plat|vt] [borrowsPerTask] [timeoutMs] [waits]
//   pool: bag(HikariCP 原版) cbag(加计数器的副本) abq(ArrayBlockingQueue 一把非公平锁) fifo(一把锁+严格先来先得递交) sync(synchronized+wait/notify)
//   hold: 0 | park:微秒 | spin:纳秒
public class Bench {
  static final class E implements IConcurrentBagEntry {
    final int id; final AtomicInteger st = new AtomicInteger();
    E(int id) { this.id = id; }
    public boolean compareAndSet(int a, int b) { return st.compareAndSet(a, b); }
    public void setState(int s) { st.set(s); }
    public int getState() { return st.get(); }
  }
  interface Pool { E borrow(long ms) throws InterruptedException; void back(E e); }

  static Pool make(String kind, int n) {
    switch (kind) {
      case "bag": { var b = new ConcurrentBag<E>(w -> {}); for (int i = 0; i < n; i++) b.add(new E(i));
        return new Pool() { public E borrow(long ms) throws InterruptedException { return b.borrow(ms, TimeUnit.MILLISECONDS); } public void back(E e) { b.requite(e); } }; }
      case "cbag": { var b = new CountingBag<E>(w -> {}); for (int i = 0; i < n; i++) b.add(new E(i)); CBAG = b;
        return new Pool() { public E borrow(long ms) throws InterruptedException { return b.borrow(ms, TimeUnit.MILLISECONDS); } public void back(E e) { b.requite(e); } }; }
      case "ex": { var b = new ExBag<E>(w -> {}); for (int i = 0; i < n; i++) b.add(new E(i)); EX = b;
        return new Pool() { public E borrow(long ms) throws InterruptedException { return b.borrow(ms, TimeUnit.MILLISECONDS); } public void back(E e) { b.requite(e); } }; }
      case "abq": { var q = new ArrayBlockingQueue<E>(n, false); for (int i = 0; i < n; i++) q.add(new E(i));
        return new Pool() { public E borrow(long ms) throws InterruptedException { return q.poll(ms, TimeUnit.MILLISECONDS); } public void back(E e) { q.offer(e); } }; }
      case "fifo": return new Fifo(n);
      case "sync": return new Sync(n);
    }
    throw new IllegalArgumentException(kind);
  }
  static volatile CountingBag<E> CBAG; static volatile ExBag<E> EX;

  // 一把锁 + 等待者队列：还回来的连接直接塞给排在最前面的人（Tomcat JDBC FairBlockingQueue 的思路）
  static final class Fifo implements Pool {
    final ArrayDeque<E> idle = new ArrayDeque<>(); final ArrayDeque<CompletableFuture<E>> waiters = new ArrayDeque<>();
    Fifo(int n) { for (int i = 0; i < n; i++) idle.push(new E(i)); }
    public E borrow(long ms) throws InterruptedException {
      CompletableFuture<E> f;
      synchronized (this) { if (waiters.isEmpty() && !idle.isEmpty()) return idle.pop(); f = new CompletableFuture<>(); waiters.add(f); }
      try { return f.get(ms, TimeUnit.MILLISECONDS); }
      catch (TimeoutException t) { synchronized (this) { if (waiters.remove(f)) return null; } return f.join(); }
      catch (ExecutionException x) { throw new IllegalStateException(x); }
    }
    public void back(E e) {
      CompletableFuture<E> f;
      synchronized (this) { f = waiters.poll(); if (f == null) { idle.push(e); return; } }
      f.complete(e);
    }
  }
  // 最朴素的写法：synchronized + wait/notify
  static final class Sync implements Pool {
    final ArrayDeque<E> idle = new ArrayDeque<>();
    Sync(int n) { for (int i = 0; i < n; i++) idle.push(new E(i)); }
    public synchronized E borrow(long ms) throws InterruptedException {
      long end = System.nanoTime() + ms * 1_000_000L;
      while (idle.isEmpty()) { long left = end - System.nanoTime(); if (left <= 0) return null; TimeUnit.NANOSECONDS.timedWait(this, left); }
      return idle.pop();
    }
    public synchronized void back(E e) { idle.push(e); notify(); }
  }

  static void hold(String h) {
    if (h.equals("0")) return;
    long v = Long.parseLong(h.substring(h.indexOf(':') + 1));
    if (h.startsWith("park")) LockSupport.parkNanos(v * 1000);
    else { long end = System.nanoTime() + v; while (System.nanoTime() < end) Thread.onSpinWait(); }
  }

  public static void main(String[] a) throws Exception {
    String kind = a[0]; int T = Integer.parseInt(a[1]), P = Integer.parseInt(a[2]); String h = a[3];
    double secs = Double.parseDouble(a[4]); String mode = a.length > 5 ? a[5] : "plat";
    int K = a.length > 6 ? Integer.parseInt(a[6]) : 1; long tmo = a.length > 7 ? Long.parseLong(a[7]) : 30_000;
    boolean rec = a.length > 8 && a[8].equals("waits"); long think = a.length > 9 ? Long.parseLong(a[9]) : 0;
    Pool pool = make(kind, P);
    LongAdder ops = new LongAdder(), tmos = new LongAdder();
    ConcurrentLinkedQueue<long[]> all = new ConcurrentLinkedQueue<>();
    long warm = System.nanoTime() + 1_000_000_000L, end = warm + (long) (secs * 1e9);
    if (mode.equals("plat")) {
      Thread[] ts = new Thread[T];
      for (int t = 0; t < T; t++) {
        ts[t] = new Thread(() -> {
          long[] w = new long[1 << 16]; int n = 0;
          try {
            while (true) {
              long s = System.nanoTime(); if (s > end) break;
              E e = pool.borrow(tmo); long got = System.nanoTime();
              if (e == null) { if (s > warm) tmos.increment(); continue; }
              hold(h); pool.back(e);
              if (think > 0) LockSupport.parkNanos(think * 1000);
              if (s > warm) { ops.increment(); if (rec) { if (n == w.length) { all.add(w); w = new long[1 << 16]; n = 0; } w[n++] = (got - s) / 1000; } }
            }
          } catch (InterruptedException ie) { }
          if (rec) all.add(Arrays.copyOf(w, n));
        });
        ts[t].start();
      }
      for (Thread t : ts) t.join();
    } else { // vt：每个任务一条新虚拟线程，同时最多 T 个，每个任务借 K 次
      Semaphore sem = new Semaphore(T);
      try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
        while (System.nanoTime() < end) {
          sem.acquire();
          ex.submit(() -> {
            long[] w = new long[K]; int n = 0;
            try {
              for (int k = 0; k < K; k++) {
                long s = System.nanoTime(); E e = pool.borrow(tmo); long got = System.nanoTime();
                if (e == null) { if (s > warm) tmos.increment(); continue; }
                hold(h); pool.back(e);
                if (s > warm) { ops.increment(); w[n++] = (got - s) / 1000; }
              }
            } catch (InterruptedException ie) { } finally { sem.release(); }
            if (rec && n > 0) all.add(Arrays.copyOf(w, n));
            return null;
          });
        }
      }
    }
    StringBuilder out = new StringBuilder(String.format("%-4s %-4s T=%-3d P=%-2d hold=%-9s think=%-4d ops/s=%-7.0f tmo=%d", kind, mode, T, P, h, think, ops.sum() / secs, tmos.sum()));
    if (rec) {
      int tot = all.stream().mapToInt(x -> x.length).sum(); long[] w = new long[tot]; int i = 0;
      for (long[] x : all) { System.arraycopy(x, 0, w, i, x.length); i += x.length; }
      Arrays.sort(w); double mean = Arrays.stream(w).average().orElse(0);
      java.util.function.DoubleFunction<Long> q = p -> w.length == 0 ? 0 : w[Math.min(w.length - 1, (int) (p * w.length))];
      out.append(String.format(" wait(us) mean=%.0f p50=%d p90=%d p99=%d p999=%d max=%d zero%%=%.1f", mean, q.apply(.5), q.apply(.9), q.apply(.99), q.apply(.999), w.length == 0 ? 0 : w[w.length - 1],
          100.0 * Arrays.stream(w).filter(x -> x < 20).count() / Math.max(1, w.length)));
    }
    var c = CBAG;
    if (c != null) out.append(String.format("%n     borrow: pocket=%d stale=%d shared=%d steal=%d handoff=%d robbed=%d timeout=%d | requite: handoff=%d taken=%d pocket=%d spin=%d",
        c.pocketHit.sum(), c.pocketStale.sum(), c.sharedHit.sum(), c.sharedSteal.sum(), c.handoffHit.sum(), c.handoffRobbed.sum(), c.timedOut.sum(),
        c.reqHandoff.sum(), c.reqTaken.sum(), c.reqPocket.sum(), c.reqSpin.sum()));
    var x = EX;
    if (x != null) out.append(String.format("%n     borrow: pocket=%d shared=%d steal=%d handoff=%d timeout=%d | requite: handoff=%d pocket=%d spin=%d",
        x.pocketHit.sum(), x.sharedHit.sum(), x.sharedSteal.sum(), x.handoffHit.sum(), x.timedOut.sum(), x.reqHandoff.sum(), x.reqPocket.sum(), x.reqSpin.sum()));
    System.out.println(out);
  }
}
