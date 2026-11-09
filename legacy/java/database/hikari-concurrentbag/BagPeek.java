import com.zaxxer.hikari.util.ConcurrentBag;
import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

// 运行：java -cp HikariCP-6.3.3.jar:slf4j-api-2.0.x.jar BagPeek.java（JDK 11+）

// 直接用 HikariCP 里的 ConcurrentBag，不连数据库：Conn 只是一个带 state 的小对象
public class BagPeek {
  static class Conn implements IConcurrentBagEntry {
    final String id; final AtomicInteger state = new AtomicInteger(); // 0 空闲 1 借出
    Conn(String id) { this.id = id; }
    public boolean compareAndSet(int e, int n) { return state.compareAndSet(e, n); }
    public void setState(int s) { state.set(s); }
    public int getState() { return state.get(); }
    public String toString() { return id; }
  }
  static ConcurrentBag<Conn> bag(int n) {
    var b = new ConcurrentBag<Conn>(waiting -> {});        // 不补新连接
    for (int i = 0; i < n; i++) b.add(new Conn("c" + i));
    return b;
  }

  public static void main(String[] args) throws Exception {
    // ① 口袋：A、B 各借一次再还，c0、c1 分别进了 A、B 的 ThreadLocal 口袋
    var bag = bag(3);
    var a = Executors.newSingleThreadExecutor(r -> new Thread(r, "A"));
    var b = Executors.newSingleThreadExecutor(r -> new Thread(r, "B"));
    Conn ca = a.submit(() -> bag.borrow(1, TimeUnit.SECONDS)).get();
    Conn cb = b.submit(() -> bag.borrow(1, TimeUnit.SECONDS)).get();
    a.submit(() -> bag.requite(ca)).get();
    b.submit(() -> bag.requite(cb)).get();
    System.out.println("① B 再借：" + b.submit(() -> { var c = bag.borrow(1, TimeUnit.SECONDS); bag.requite(c); return c; }).get()
        + "（c0 也空着，B 先翻自己的口袋）");

    // ② 偷：新线程 C 口袋是空的，扫货架（sharedList）拿走 c0；A 口袋里那条 c0 就过期了
    var stolen = Executors.newSingleThreadExecutor(r -> new Thread(r, "C")).submit(() -> bag.borrow(1, TimeUnit.SECONDS)).get();
    System.out.println("② C 拿到：" + stolen + "；A 再借：" + a.submit(() -> bag.borrow(1, TimeUnit.SECONDS)).get()
        + "（口袋里的 c0 已被 C 借走，CAS 失败，改去扫货架）");
    a.shutdown(); b.shutdown();

    // ③ 递交：只有 1 个连接，W 在等；main 一还，就 offer 给停在 poll 上的 W
    var one = bag(1);
    Conn held = one.borrow(1, TimeUnit.SECONDS);
    var w = CompletableFuture.supplyAsync(() -> { try { long t = System.nanoTime(); var c = one.borrow(2, TimeUnit.SECONDS);
        return "W 拿到 " + c + "，等了 " + (System.nanoTime() - t) / 1_000_000 + " ms"; } catch (InterruptedException e) { return "?"; } });
    Thread.sleep(200);
    System.out.println("③ 还之前 waiters = " + one.getWaitingThreadCount());
    one.requite(held);
    System.out.println("   " + w.get());

    // ④ 插队：还完马上又借，W 被唤醒前，还的人自己把连接抢了回去
    int trials = 1000, robbed = 0;
    for (int i = 0; i < trials; i++) {
      var single = bag(1);
      Conn mine = single.borrow(1, TimeUnit.SECONDS);
      var waiter = CompletableFuture.supplyAsync(() -> { try { return single.borrow(1, TimeUnit.SECONDS); } catch (InterruptedException e) { return null; } });
      while (single.getWaitingThreadCount() == 0) Thread.onSpinWait();
      Thread.sleep(1);                        // 让 W 停进 poll
      single.requite(mine);                   // 还：递给 W
      Conn again = single.borrow(0, TimeUnit.MILLISECONDS); // 马上又借
      if (again != null) { robbed++; single.requite(again); }   // 再还一次，W 这回才拿到
      waiter.get();
    }
    System.out.println("④ 还完马上又借，1000 次里抢回来 " + robbed + " 次；这些次里 W 白醒一回，CAS 失败后重新 poll");
  }
}
