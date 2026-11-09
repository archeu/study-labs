import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TpePits {
  public static void main(String[] a) throws Exception {
    // ① execute 里抛异常：这个线程死掉，池子换一个新的
    var p1 = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    var names = Collections.synchronizedList(new ArrayList<String>());
    p1.execute(() -> names.add(Thread.currentThread().getName()));
    p1.execute(() -> { names.add(Thread.currentThread().getName()); throw new IllegalStateException("boom"); });
    Thread.sleep(200);
    p1.execute(() -> names.add(Thread.currentThread().getName()));
    Thread.sleep(200);
    System.out.println("① execute 抛异常前后的线程名：" + names + "，completedTaskCount = " + p1.getCompletedTaskCount());
    // submit：异常被装进 Future，线程不死，什么也不打印
    names.clear();
    Future<?> f = p1.submit(() -> { names.add(Thread.currentThread().getName()); throw new IllegalStateException("boom2"); });
    Thread.sleep(200);
    p1.execute(() -> names.add(Thread.currentThread().getName()));
    Thread.sleep(200);
    String got; try { f.get(); got = "没异常"; } catch (ExecutionException e) { got = "get() 抛 ExecutionException(" + e.getCause().getMessage() + ")"; }
    System.out.println("   submit 抛异常前后的线程名：" + names + "，" + got);
    p1.shutdown();

    // ② CallerRunsPolicy + 已经 shutdown：任务被悄悄丢掉
    var p2 = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
    p2.shutdown();
    var ran = new AtomicInteger();
    for (int i = 0; i < 100; i++) p2.execute(ran::incrementAndGet);
    System.out.println("② CallerRunsPolicy，关池后提交 100 个：执行了 " + ran.get() + " 个，没有异常");

    // ③ shutdown 和 shutdownNow
    for (boolean now : new boolean[] {false, true}) {
      var p3 = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
      var done = new AtomicInteger(); var interrupted = new AtomicInteger();
      p3.execute(() -> { try { Thread.sleep(300); done.incrementAndGet(); } catch (InterruptedException e) { interrupted.incrementAndGet(); } });
      for (int i = 0; i < 5; i++) p3.execute(() -> { try { Thread.sleep(50); done.incrementAndGet(); } catch (InterruptedException e) { interrupted.incrementAndGet(); } });
      Thread.sleep(50);
      List<Runnable> left = now ? p3.shutdownNow() : List.of();
      if (!now) p3.shutdown();
      p3.awaitTermination(5, TimeUnit.SECONDS);
      System.out.printf("③ %-11s：跑完 %d 个，被打断 %d 个，退回 %d 个没跑的%n", now ? "shutdownNow" : "shutdown", done.get(), interrupted.get(), left.size());
    }
    // ④ 不响应中断的任务：shutdownNow 也停不下来
    var p4 = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    var stop = new AtomicBoolean();
    p4.execute(() -> { while (!stop.get()) { } });
    Thread.sleep(50);
    p4.shutdownNow();
    System.out.println("④ 忙循环不看中断：shutdownNow 后 1 秒内结束了吗？" + p4.awaitTermination(1, TimeUnit.SECONDS));
    stop.set(true); p4.awaitTermination(1, TimeUnit.SECONDS);
  }
}
