import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

// 极简双重队列（FIFO）：只有 put（等到被取走才返回）和 take，不支持超时和中断
public class MiniDualQueue<E> {
  static final class Node {
    final boolean isData;                                  // true：数据节点；false：请求节点
    final AtomicReference<Object> item;                    // 数据节点 x→null、请求节点 null→x，就是“交接”
    final AtomicReference<Node> next = new AtomicReference<>();
    volatile Thread waiter;
    Node(Object x, boolean isData) { this.item = new AtomicReference<>(x); this.isData = isData; }
  }
  static final int SPINS = Integer.getInteger("spins", 128);
  private final AtomicReference<Node> head, tail;
  public MiniDualQueue() {
    Node dummy = new Node(null, true);                     // 哑节点：isData 却没数据，算“已配对”
    head = new AtomicReference<>(dummy); tail = new AtomicReference<>(dummy);
  }
  public void put(E e) { xfer(e); }
  @SuppressWarnings("unchecked") public E take() { return (E) xfer(null); }

  private Object xfer(Object e) {
    boolean haveData = (e != null);
    Node s = null;
    for (;;) {
      Node t = tail.get(), h = head.get();
      if (h == t || t.isData == haveData) {                // 空，或者排着的是同类：排到队尾
        Node tn = t.next.get();
        if (t != tail.get()) continue;
        if (tn != null) { tail.compareAndSet(t, tn); continue; }   // tail 落后了，帮它往前推
        if (s == null) s = new Node(e, haveData);
        if (!t.next.compareAndSet(null, s)) continue;      // 别人先追加了，重来
        tail.compareAndSet(t, s);
        Object x = await(s, e);
        if (head.get() == t) head.compareAndSet(t, s);     // 帮忙把 head 推过自己
        return (x != null) ? x : e;
      } else {                                             // 排着的是异类：和第一个配对
        Node m = h.next.get();
        if (t != tail.get() || m == null || h != head.get()) continue;   // 读到的不一致，重来
        Object x = m.item.get();
        boolean ok = (haveData == (x == null)) && m.item.compareAndSet(x, e);
        head.compareAndSet(h, m);                          // 不管成没成功，m 都不再是活节点了
        if (!ok) continue;                                 // 被别人抢先配对了
        LockSupport.unpark(m.waiter);
        return (x != null) ? x : e;
      }
    }
  }
  private Object await(Node s, Object e) {                 // 先自旋，再挂起
    for (int spins = SPINS;;) {
      Object x = s.item.get();
      if (x != e) return x;
      if (spins > 0) { spins--; Thread.onSpinWait(); }
      else if (s.waiter == null) s.waiter = Thread.currentThread();   // 先登记，再复查一遍 item
      else LockSupport.park(this);
    }
  }
}
