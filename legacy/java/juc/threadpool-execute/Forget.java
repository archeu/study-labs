import java.util.concurrent.*;
public class Forget { public static void main(String[] a) {
  Executors.newFixedThreadPool(2).execute(() -> System.out.println("任务跑完了"));
  System.gc(); System.out.println("main 返回了，没调 shutdown"); } }
