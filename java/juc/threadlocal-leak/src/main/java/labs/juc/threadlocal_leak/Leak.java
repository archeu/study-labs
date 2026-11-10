package labs.juc.threadlocal_leak;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 泄漏：池里只有 1 个线程，跑 N 个“请求”，每个请求往 ThreadLocal 里放 1 MB。
 *   static     static final ThreadLocal，不 remove：值被覆盖，线程活着就一直占 1 MB
 *   perobj     每个请求 new 一个 ThreadLocal（像把它写成实例字段），不 remove：
 *              key 被 GC 清成 null，value 还挂在线程上，等下次碰巧被清理
 *   remove     每个请求 new ThreadLocal，但 finally remove
 * 运行要加 --add-opens java.base/java.lang=ALL-UNNAMED，用反射数线程里的 ThreadLocalMap。
 */
public class Leak {
    static final int MB = 1 << 20;
    static final ThreadLocal<byte[]> STATIC_TL = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "perobj";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "exec-1"));
        gc();
        long base = used();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> request(mode)).get();
        }
        System.out.printf("模式 %s，%d 个请求跑完%n", mode, n);
        pool.submit(() -> inspect("请求刚结束")).get();
        gc();
        pool.submit(() -> inspect("Full GC 之后")).get();
        System.out.printf("  堆比开始时多占 %.1f MB%n", (used() - base) / (double) MB);
        // 再来一个请求：新 ThreadLocal 的 set 会顺手扫几格，碰到 key 为 null 的就清
        pool.submit(() -> { ThreadLocal<String> t = new ThreadLocal<>(); t.set("x"); t.remove(); }).get();
        gc();
        pool.submit(() -> inspect("再来一次 set 之后")).get();
        System.out.printf("  堆比开始时多占 %.1f MB%n", (used() - base) / (double) MB);
        pool.shutdown();
    }

    static void request(String mode) {
        byte[] big = new byte[MB];
        if (mode.equals("static")) {
            STATIC_TL.set(big);
        } else {
            ThreadLocal<byte[]> tl = new ThreadLocal<>();
            tl.set(big);
            try {
                tl.get();
            } finally {
                if (mode.equals("remove")) tl.remove();
            }
        }
    }

    /** 用反射数当前线程 ThreadLocalMap 里的条目：总数、key 已被清成 null 的、它们挂着的字节数 */
    static void inspect(String label) {
        try {
            Field f = Thread.class.getDeclaredField("threadLocals");
            f.setAccessible(true);
            Object map = f.get(Thread.currentThread());
            if (map == null) { System.out.printf("  [%s] 线程上没有 ThreadLocalMap%n", label); return; }
            Field tf = map.getClass().getDeclaredField("table");
            tf.setAccessible(true);
            Object[] table = (Object[]) tf.get(map);
            int live = 0, stale = 0; long staleBytes = 0, liveBytes = 0;
            for (Object e : table) {
                if (e == null) continue;
                Field vf = e.getClass().getDeclaredField("value");
                vf.setAccessible(true);
                Object v = vf.get(e);
                long bytes = v instanceof byte[] ? ((byte[]) v).length : 0;
                if (((Reference<?>) e).get() == null) { stale++; staleBytes += bytes; }
                else { live++; liveBytes += bytes; }
            }
            System.out.printf("  [%s] 表长 %d，活条目 %d（%.0f MB），key=null 的条目 %d（还挂着 %.0f MB）%n",
                    label, table.length, live, liveBytes / (double) MB, stale, staleBytes / (double) MB);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("需要 --add-opens java.base/java.lang=ALL-UNNAMED", ex);
        }
    }

    static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(50); }
    }

    static long used() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
