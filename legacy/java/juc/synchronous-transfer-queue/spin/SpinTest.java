import java.nio.file.*;
import java.util.concurrent.*;
public class SpinTest {
  static long[] proc() throws Exception { long ctx = 0;
    try (var ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) { for (Path t : ds) { try { for (String l : Files.readAllLines(t.resolve("status"))) if (l.contains("ctxt_switches")) ctx += Long.parseLong(l.replaceAll("\\D", "")); } catch (NoSuchFileException gone) { } } }
    String[] st = Files.readString(Paths.get("/proc/self/stat")).split(" "); return new long[] {ctx, Long.parseLong(st[13]) + Long.parseLong(st[14])}; }
  public static void main(String[] a) throws Exception {
    int P = Integer.parseInt(a[0]); long M = Long.parseLong(a[1]);
    var q = new MiniDualQueue<Long>(); Thread[] ts = new Thread[2 * P];
    var done = new CountDownLatch(2 * P); var exit = new CountDownLatch(1); var go = new CountDownLatch(1);
    Runnable park = () -> { done.countDown(); try { exit.await(); } catch (InterruptedException e) { } };
    for (int i = 0; i < P; i++) {
      ts[i] = new Thread(() -> { try { go.await(); } catch (InterruptedException e) { } for (long v = 0; v < M; v++) q.put(v); park.run(); });
      ts[P + i] = new Thread(() -> { try { go.await(); } catch (InterruptedException e) { } for (long v = 0; v < M; v++) q.take(); park.run(); });
    }
    for (Thread t : ts) t.start();
    Thread.sleep(200);
    long[] b = proc(); long t0 = System.nanoTime(); go.countDown();
    done.await(); long t1 = System.nanoTime(); long[] e = proc(); exit.countDown(); for (Thread t : ts) t.join();
    double s = (t1 - t0) / 1e9, n = (double) P * M;
    System.out.printf("spins=%-5d pairs=%d  %.0f 万次/秒  切换/次=%.2f  CPU 秒/百万次=%.2f%n", MiniDualQueue.SPINS, P, n / s / 1e4, (e[0] - b[0] - 4.0 * P) / n, (e[1] - b[1]) / 100.0 / (n / 1e6));
  }
}
