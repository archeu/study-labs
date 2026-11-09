import java.util.concurrent.*;
public class Dump { public static void main(String[] a) throws Exception {
  var p = Executors.newFixedThreadPool(2); for (int i = 0; i < 2; i++) p.execute(() -> { try { Thread.sleep(20000); } catch (InterruptedException e) { } });
  System.out.println("ready"); Thread.sleep(20000); p.shutdownNow(); } }
