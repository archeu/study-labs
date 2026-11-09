import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// SynchronousQueue 在线程池里的三种后果
public class PoolPeek {
  public static void main(String[] a) throws Exception {
    // ① newCachedThreadPool：1000 个任务一下子进来，每个睡 200 ms
    var cached = (ThreadPoolExecutor) Executors.newCachedThreadPool();
    for (int i = 0; i < 1000; i++) cached.execute(() -> sleep(200));
    System.out.println("① 突发 1000 个任务：线程数 = " + cached.getPoolSize() + "，队列长度 = " + cached.getQueue().size());
    cached.shutdown(); cached.awaitTermination(10, TimeUnit.SECONDS);

    // ② 上限 50 个线程 + SynchronousQueue：第 51 个任务直接被拒
    var bounded = new ThreadPoolExecutor(0, 50, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
    int rejected = 0;
    for (int i = 0; i < 1000; i++) { try { bounded.execute(() -> sleep(200)); } catch (RejectedExecutionException e) { rejected++; } }
    System.out.println("② 上限 50：线程数 = " + bounded.getPoolSize() + "，被拒 " + rejected + " 个");
    bounded.shutdown(); bounded.awaitTermination(10, TimeUnit.SECONDS);

    // ③ 先让 10 个线程都忙起来，之后每 100 ms 来一个很短的任务，空闲 2 s 就回收
    for (boolean fair : new boolean[] {false, true}) {
      var pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 2, TimeUnit.SECONDS, new SynchronousQueue<>(fair));
      var latch = new CountDownLatch(10);
      for (int i = 0; i < 10; i++) pool.execute(() -> { latch.countDown(); sleep(300); });
      latch.await(); sleep(400);                       // 10 个线程都回到 poll 上等活
      var names = ConcurrentHashMap.<String>newKeySet();
      for (int i = 0; i < 60; i++) { pool.execute(() -> names.add(Thread.currentThread().getName())); sleep(100); }
      System.out.println("③ SynchronousQueue(" + fair + ")：6 秒后线程数 = " + pool.getPoolSize() + "，干过活的线程 " + names.size() + " 个");
      pool.shutdown();
    }
  }
  static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }
}
