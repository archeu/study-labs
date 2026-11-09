import com.zaxxer.hikari.util.ConcurrentBag;
import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.AtomicInteger;
public class WeakCheck {
  static class C implements IConcurrentBagEntry { final AtomicInteger s = new AtomicInteger();
    public boolean compareAndSet(int a, int b) { return s.compareAndSet(a, b); } public void setState(int v) { s.set(v); } public int getState() { return s.get(); } }
  public static void main(String[] a) throws Exception {
    var bag = new ConcurrentBag<C>(w -> {}); var c = new C(); bag.add(c);
    var f = ConcurrentBag.class.getDeclaredField("threadLocalList"); f.setAccessible(true);
    var w = ConcurrentBag.class.getDeclaredField("useWeakThreadLocals"); w.setAccessible(true);
    @SuppressWarnings("unchecked") List<Object> pocket = ((ThreadLocal<List<Object>>) f.get(bag)).get();
    var x = bag.borrow(1, TimeUnit.SECONDS); bag.requite(x);
    System.out.println("weak=" + w.get(bag) + " 还完口袋里: " + pocket.size() + " 个 " + pocket.get(0).getClass().getSimpleName());
    x = bag.borrow(1, TimeUnit.SECONDS); bag.requite(x);   // 再借再还，口袋还是 1 个
    x = bag.borrow(1, TimeUnit.SECONDS);                     // 借出（口袋被取空）
    bag.requite(x); System.out.println("再还一次后口袋: " + pocket.size());
    // 管家式下架：reserve -> remove
    System.out.println("reserve=" + bag.reserve(c) + " remove=" + bag.remove(c) + " remove 后口袋: " + pocket.size());
    // 16 上限
    var bag2 = new ConcurrentBag<C>(q -> {}); List<C> held = new ArrayList<>();
    for (int i = 0; i < 20; i++) bag2.add(new C());
    for (int i = 0; i < 20; i++) held.add(bag2.borrow(1, TimeUnit.SECONDS));
    for (C h : held) bag2.requite(h);
    @SuppressWarnings("unchecked") List<Object> p2 = ((ThreadLocal<List<Object>>) f.get(bag2)).get();
    System.out.println("一次还 20 个，口袋只收: " + p2.size());
  }
}
