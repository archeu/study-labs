import java.util.*;
import java.util.concurrent.*;

// 看 SynchronousQueue 和 LinkedTransferQueue 的行为；JDK 11+ 直接 java SqPeek.java
public class SqPeek {
  // 让 n 个线程按 A、B、C… 的先后顺序停在 take() 上，再依次放入 1..n，看谁拿到几
  static String order(BlockingQueue<Integer> q, int n) throws Exception {
    var got = new ConcurrentSkipListMap<Integer, String>();
    var ts = new ArrayList<Thread>();
    for (int i = 0; i < n; i++) {
      String name = String.valueOf((char) ('A' + i));
      Thread t = new Thread(() -> { try { got.put(q.take(), name); } catch (InterruptedException e) { } });
      t.start(); ts.add(t);
      while (t.getState() != Thread.State.WAITING) Thread.sleep(1);   // 确认它已经停住
    }
    for (int v = 1; v <= n; v++) q.put(v);
    for (Thread t : ts) t.join();
    return got.toString();
  }

  public static void main(String[] args) throws Exception {
    System.out.println("JDK " + Runtime.version());
    var tr = Class.forName("java.util.concurrent.SynchronousQueue$Transferer");
    System.out.println("① SynchronousQueue$Transferer 的父类：" + tr.getSuperclass().getName());

    var sq = new SynchronousQueue<String>();
    System.out.println("② 没人在取：offer(\"x\") = " + sq.offer("x") + "，poll() = " + sq.poll() + "，size() = " + sq.size());
    Thread waiting = new Thread(() -> { try { sq.take(); } catch (InterruptedException e) { } });
    waiting.start(); while (waiting.getState() != Thread.State.WAITING) Thread.sleep(1);
    System.out.println("   有人停在 take() 上：size() = " + sq.size() + "，isEmpty() = " + sq.isEmpty() + "，offer(\"x\") = " + sq.offer("x"));
    waiting.join();

    System.out.println("③ 默认（不公平）：" + order(new SynchronousQueue<>(), 3) + "  ← 值 → 拿到它的线程");
    System.out.println("   公平 true：   " + order(new SynchronousQueue<>(true), 3));

    var ltq = new LinkedTransferQueue<String>();
    ltq.put("a"); ltq.put("b");
    System.out.println("④ LTQ put 两次就返回：size() = " + ltq.size() + "；tryTransfer(\"c\") = " + ltq.tryTransfer("c") + "（没人在等）");
    ltq.clear();
    Thread consumer = new Thread(() -> { try { Thread.sleep(300); System.out.println("   消费者 300 ms 后 take() = " + ltq.take()); } catch (InterruptedException e) { } });
    consumer.start();
    long t0 = System.nanoTime(); ltq.transfer("d");
    System.out.println("   transfer(\"d\") 等了 " + (System.nanoTime() - t0) / 1_000_000 + " ms 才返回");
    consumer.join();
    Thread c2 = new Thread(() -> { try { ltq.take(); } catch (InterruptedException e) { } });
    c2.start(); while (c2.getState() != Thread.State.WAITING) Thread.sleep(1);
    System.out.println("   有人在等：hasWaitingConsumer() = " + ltq.hasWaitingConsumer() + "，getWaitingConsumerCount() = " + ltq.getWaitingConsumerCount() + "，size() = " + ltq.size());
    ltq.put("e"); c2.join();
  }
}
