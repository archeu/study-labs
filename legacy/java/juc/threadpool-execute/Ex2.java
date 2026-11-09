import java.util.concurrent.*;
public class Ex2 { public static void main(String[] a) throws Exception {
  var p = new ThreadPoolExecutor(10, 10, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
  p.allowCoreThreadTimeOut(true);
  long t0 = System.nanoTime();
  var done = new CountDownLatch(10);
  for (int i = 0; i < 10; i++) p.execute(() -> { try { Thread.sleep(100); } catch (InterruptedException e) { } done.countDown(); });
  done.await();
  System.out.printf("10 个 100 ms 的任务用了 %d ms，线程数 %d%n", (System.nanoTime() - t0) / 1_000_000, p.getPoolSize());
  Thread.sleep(1500);
  System.out.println("空闲 1.5 s 后线程数 " + p.getPoolSize());
  p.execute(() -> {}); Thread.sleep(50);
  System.out.println("再来一个任务，线程数 " + p.getPoolSize() + "，最大值 " + p.getLargestPoolSize());
  p.shutdown();
  try { p.execute(() -> {}); } catch (RejectedExecutionException e) { System.out.println("shutdown 后 execute：" + e.getClass().getSimpleName()); }
} }
