import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

// 看 execute 的三步判断、任务的实际执行顺序、ctl 的位
// 运行：java --add-opens java.base/java.util.concurrent=ALL-UNNAMED TpePeek.java
public class TpePeek {
  static Field CTL;
  static String ctl(ThreadPoolExecutor p) throws Exception {
    int c = ((AtomicInteger) CTL.get(p)).get();
    String[] names = {"RUNNING", "SHUTDOWN", "STOP", "TIDYING", "TERMINATED"};
    int rs = c >> 29;                                   // 高 3 位
    String bits = String.format("%32s", Integer.toBinaryString(c)).replace(' ', '0');
    return bits.substring(0, 3) + " " + bits.substring(3) + "  →  " + names[rs + 1] + "，线程数 " + (c & ((1 << 29) - 1));
  }
  public static void main(String[] a) throws Exception {
    CTL = ThreadPoolExecutor.class.getDeclaredField("ctl"); CTL.setAccessible(true);
    var started = Collections.synchronizedList(new ArrayList<String>());
    var pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2));
    System.out.println("新建：        " + ctl(pool));
    for (int i = 1; i <= 8; i++) {
      final int id = i;
      String how;
      try {
        pool.execute(() -> { started.add("#" + id); sleep(300); });
        how = "线程 " + pool.getPoolSize() + "，队列 " + pool.getQueue().size();
      } catch (RejectedExecutionException e) { how = "被拒绝"; }
      System.out.printf("execute(#%d) → %s%n", i, how);
    }
    System.out.println("此刻：        " + ctl(pool));
    sleep(1000);
    System.out.println("开跑顺序：" + started);
    pool.shutdown();
    System.out.println("shutdown 后： " + ctl(pool));
    pool.awaitTermination(5, TimeUnit.SECONDS);
    System.out.println("全部结束：    " + ctl(pool));

    // core = 0 + 无界队列：只会有 1 个线程
    var zero = new ThreadPoolExecutor(0, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    long t0 = System.nanoTime();
    for (int i = 0; i < 10; i++) zero.execute(() -> sleep(100));
    zero.shutdown(); zero.awaitTermination(10, TimeUnit.SECONDS);
    System.out.printf("core=0、max=10、无界队列：最多同时 %d 个线程，10 个 100 ms 的任务用了 %d ms%n", zero.getLargestPoolSize(), (System.nanoTime() - t0) / 1_000_000);
  }
  static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }
}
