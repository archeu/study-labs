import java.util.*;
import java.util.concurrent.*;
// core=1、max=2：两个线程都闲下来以后，留下的“核心线程”是哪一个？
public class CoreId { public static void main(String[] a) throws Exception {
  for (String longer : new String[] {"T1", "T2"}) {
    var survivors = new TreeMap<String, Integer>();
    for (int run = 0; run < 10; run++) {
      var created = new ArrayList<Thread>();
      ThreadFactory tf = r -> { Thread t = new Thread(r, "T" + (created.size() + 1)); created.add(t); return t; };
      var p = new ThreadPoolExecutor(1, 2, 100, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), tf);
      for (int i = 1; i <= 2; i++) { final long ms = longer.equals("T" + i) ? 80 : 10; p.execute(() -> { try { Thread.sleep(ms); } catch (InterruptedException e) { } }); }
      Thread.sleep(700);
      String alive = created.stream().filter(Thread::isAlive).map(Thread::getName).reduce((x, y) -> x + "+" + y).orElse("无");
      survivors.merge(alive, 1, Integer::sum);
      p.shutdownNow();
    }
    System.out.println(longer + " 的任务跑得久：10 次里活下来的是 " + survivors + "（T1 是按核心名额建的，T2 是临时名额）");
  }
} }
