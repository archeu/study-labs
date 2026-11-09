import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// 压力测试：P 个生产者各放 M 个数，C 个消费者一共取 P*M 个，检查一个不多一个不少
public class MiniTest {
  public static void main(String[] a) throws Exception {
    int P = Integer.parseInt(a[0]), C = Integer.parseInt(a[1]), M = Integer.parseInt(a[2]);
    var q = new MiniDualQueue<Long>();
    var sum = new LongAdder(); var cnt = new LongAdder(); var dup = new ConcurrentHashMap<Long, Boolean>(); var dups = new LongAdder();
    long total = (long) P * M, expect = 0;
    for (long v = 0; v < total; v++) expect += v;
    var start = new CountDownLatch(1);
    Thread[] ps = new Thread[P], cs = new Thread[C];
    long per = total / C;
    for (int i = 0; i < P; i++) { final long base = (long) i * M; ps[i] = new Thread(() -> { try { start.await(); } catch (InterruptedException e) { } for (long v = 0; v < M; v++) q.put(base + v); }); ps[i].start(); }
    for (int i = 0; i < C; i++) { final long n = (i == C - 1) ? total - per * (C - 1) : per; cs[i] = new Thread(() -> { try { start.await(); } catch (InterruptedException e) { } for (long k = 0; k < n; k++) { long v = q.take(); sum.add(v); cnt.increment(); if (dup.put(v, true) != null) dups.increment(); } }); cs[i].start(); }
    long t0 = System.nanoTime(); start.countDown();
    for (Thread t : ps) t.join(); for (Thread t : cs) t.join();
    double s = (System.nanoTime() - t0) / 1e9;
    System.out.printf("P=%d C=%d items=%d taken=%d sumOk=%b dups=%d  %.0f ops/s%n", P, C, total, cnt.sum(), sum.sum() == expect, dups.sum(), total / s);
  }
}
