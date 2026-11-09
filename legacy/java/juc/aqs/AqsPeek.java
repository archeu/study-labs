import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

// 直接看 AQS 里面：state、排队的节点、节点状态
// 运行：java --add-opens java.base/java.util.concurrent.locks=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED AqsPeek.java
public class AqsPeek {
  static Object field(Object o, String... names) throws Exception {
    for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
      for (String n : names) try { Field f = c.getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException e) { }
    return null;
  }
  static AbstractQueuedSynchronizer sync(Object owner) throws Exception { return (AbstractQueuedSynchronizer) field(owner, "sync"); }
  static String queue(AbstractQueuedSynchronizer s) throws Exception {
    StringBuilder b = new StringBuilder("state=" + field(s, "state") + "  队列：");
    Object n = field(s, "head");
    if (n == null) return b.append("（还没建）").toString();
    for (int i = 0; n != null && i < 10; i++, n = field(n, "next")) {
      Thread t = (Thread) field(n, "waiter", "thread");                 // JDK 14+ 叫 waiter，之前叫 thread
      Object st = field(n, "status", "waitStatus");                     // JDK 14+ 叫 status，之前叫 waitStatus
      b.append(i == 0 ? "head" : " → ").append(i == 0 ? "" : (t == null ? "?" : t.getName())).append("[").append(n.getClass().getSimpleName()).append(" ").append(st).append("]");
    }
    return b.toString();
  }
  static void waitParked(Thread t) throws InterruptedException { while (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) Thread.sleep(1); }

  public static void main(String[] a) throws Exception {
    System.out.println("JDK " + Runtime.version());
    var lock = new ReentrantLock();
    lock.lock(); lock.lock();                                           // main 重入两次
    System.out.println("① main 重入 2 次：" + queue(sync(lock)) + "，" + lock);
    List<Thread> ws = new ArrayList<>();
    for (String name : new String[] {"T1", "T2", "T3"}) {
      Thread t = new Thread(() -> { lock.lock(); lock.unlock(); }, name); t.start(); waitParked(t); ws.add(t);
    }
    System.out.println("② 三个线程排队：" + queue(sync(lock)));
    lock.unlock();
    System.out.println("③ unlock 一次：" + queue(sync(lock)) + "（还剩 1 层，没人被叫醒）");
    lock.unlock(); for (Thread t : ws) t.join();
    System.out.println("④ 全部走完：  " + queue(sync(lock)));

    var sem = new Semaphore(3); sem.acquire(2);
    var latch = new CountDownLatch(2); latch.countDown();
    var rw = new ReentrantReadWriteLock(); rw.readLock().lock(); rw.readLock().lock(); rw.writeLock();
    System.out.println("⑤ Semaphore(3) 拿走 2 个：state=" + field(sync(sem), "state") + "；CountDownLatch(2) countDown 一次：state=" + field(sync(latch), "state")
        + "；读锁拿两次：state=" + field(sync(rw), "state") + "（高 16 位是读锁数，" + ((int) field(sync(rw), "state") >>> 16) + "）");

    // ⑥ W 已经在排队，持锁的线程 unlock 后马上又抢：tryLock() 和 lock() 分别谁拿到？
    for (boolean fair : new boolean[] {false, true}) {
      int tryWins = 0, lockWins = 0;
      for (int i = 0; i < 1000; i++) {
        var l = new ReentrantLock(fair); l.lock();
        Thread w = new Thread(() -> { l.lock(); l.unlock(); }); w.start(); waitParked(w);
        l.unlock();
        if (l.tryLock()) { tryWins++; l.unlock(); }
        w.join();
        var l2 = new ReentrantLock(fair); l2.lock();
        var wGot = new java.util.concurrent.atomic.AtomicBoolean();
        Thread w2 = new Thread(() -> { l2.lock(); wGot.set(true); l2.unlock(); }); w2.start(); waitParked(w2);
        l2.unlock();
        l2.lock(); if (!wGot.get()) lockWins++; l2.unlock();
        w2.join();
      }
      System.out.println("⑥ " + (fair ? "公平  " : "非公平") + "：放下马上再抢，1000 次里 tryLock() 抢到 " + tryWins + " 次，lock() 抢在 W 前面 " + lockWins + " 次");
    }
  }
}
