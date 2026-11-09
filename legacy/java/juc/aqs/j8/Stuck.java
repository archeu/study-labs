import java.io.*;
import java.util.concurrent.locks.*;
// 摆几种“卡住”的样子，让 jstack -l 看：拿着锁睡觉的、排队等锁的、在 Condition 上等的、抢 PrintStream 的
public class Stuck {
  public static void main(String[] a) throws Exception {
    final ReentrantLock lock = new ReentrantLock();
    new Thread(() -> { lock.lock(); try { Thread.sleep(60_000); } catch (InterruptedException e) { } finally { lock.unlock(); } }, "holder").start();
    Thread.sleep(200);
    new Thread(() -> { lock.lock(); lock.unlock(); }, "waiter").start();
    final ReentrantLock lock2 = new ReentrantLock(); final Condition c2 = lock2.newCondition();
    new Thread(() -> { lock2.lock(); try { c2.await(); } catch (InterruptedException e) { } finally { lock2.unlock(); } }, "awaiting").start();
    final PrintStream slow = new PrintStream(new OutputStream() {
      public void write(int b) { }
      public void write(byte[] b, int o, int l) { try { Thread.sleep(60_000); } catch (InterruptedException e) { } } }, true);
    new Thread(() -> slow.println("x"), "printer-1").start();
    Thread.sleep(100);
    new Thread(() -> slow.println("y"), "printer-2").start();
    System.out.println("ready"); Thread.sleep(60_000);
  }
}
