import java.util.concurrent.*;
import java.util.concurrent.locks.*;
public class Pits2 {
  public static void main(String[] a) throws Exception {
    ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    rw.readLock().lock();
    boolean up = rw.writeLock().tryLock(1, TimeUnit.SECONDS);       // 拿着读锁再要写锁
    System.out.println("拿着读锁再 tryLock 写锁 1 秒：" + up + (up ? "" : "（换成 lock() 就是永远卡住）"));
    rw.readLock().unlock();
    rw.writeLock().lock(); rw.readLock().lock(); rw.writeLock().unlock();   // 锁降级：写 → 读
    System.out.println("拿着写锁再拿读锁（锁降级）：成功，读锁数 " + rw.getReadLockCount());
    rw.readLock().unlock();
    Semaphore sem = new Semaphore(2);
    sem.acquire(); sem.release(); sem.release();                          // 多放了一次
    System.out.println("Semaphore(2)：acquire 1 次、release 2 次后，可用许可 " + sem.availablePermits());
    ReentrantLock l = new ReentrantLock();
    try { l.unlock(); } catch (IllegalMonitorStateException e) { System.out.println("没拿锁就 unlock：" + e.getClass().getSimpleName()); }
  }
}
