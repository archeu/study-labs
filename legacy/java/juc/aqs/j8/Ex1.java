import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.*;
// 公平锁：W 已经在排队，持锁线程放下后马上再抢。分别用 tryLock() 和 tryLock(0, SECONDS)，只数“抢在 W 前面”的次数
public class Ex1 { public static void main(String[] a) throws Exception {
  for (boolean timed : new boolean[] {false, true}) {
    int barge = 0;
    for (int i = 0; i < 1000; i++) {
      final ReentrantLock l = new ReentrantLock(true); l.lock();
      final AtomicBoolean wGot = new AtomicBoolean();
      Thread w = new Thread(() -> { l.lock(); wGot.set(true); l.unlock(); }); w.start();
      while (w.getState() != Thread.State.WAITING) Thread.sleep(1);
      l.unlock();
      boolean got = timed ? l.tryLock(0, TimeUnit.SECONDS) : l.tryLock();
      if (got) { if (!wGot.get()) barge++; l.unlock(); }
      w.join();
    }
    System.out.println("公平锁 " + (timed ? "tryLock(0, SECONDS)" : "tryLock()          ") + "：1000 次里抢在 W 前面 " + barge + " 次");
  }
} }
