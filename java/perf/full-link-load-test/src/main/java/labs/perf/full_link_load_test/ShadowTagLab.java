package labs.perf.full_link_load_test;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实验 2：压测标记在线程池里丢了，压测数据写进了真表。
 * 入口（网关 / Filter）看到请求头 x-pt: 1，就把标记放进 ThreadLocal；DAO 按标记选表：t_coupon_user 或 t_coupon_user_pt。
 * 三种“异步写一条记录”的方式，各发 100 个压测请求，数每张表收到几行。
 */
public class ShadowTagLab {

    static final ThreadLocal<Boolean> PT = new ThreadLocal<>();
    static final InheritableThreadLocal<Boolean> PT_INH = new InheritableThreadLocal<>();
    static final Map<String, AtomicInteger> TABLES = new ConcurrentHashMap<>();

    /** DAO：按标记选表。 */
    static void insert(boolean pressure) {
        String table = pressure ? "t_coupon_user_pt" : "t_coupon_user";
        TABLES.computeIfAbsent(table, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 把提交时的标记带进任务里，执行完清掉（TransmittableThreadLocal、SkyWalking 的跨线程插件做的就是这件事）。 */
    static Runnable wrap(Runnable r) {
        Boolean captured = PT.get();
        return () -> {
            Boolean old = PT.get();
            PT.set(captured);
            try { r.run(); } finally { if (old == null) PT.remove(); else PT.set(old); }
        };
    }

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        // 先让池子里的 4 个线程都被一个“真实请求”创建出来（线程池通常在压测前就有线程了）
        for (int i = 0; i < 4; i++) pool.submit(() -> { }).get();

        run("同步写（同一个线程）", pool, () -> insert(Boolean.TRUE.equals(PT.get())), false);
        run("线程池里写，ThreadLocal", pool, () -> pool.submit(() -> insert(Boolean.TRUE.equals(PT.get()))), true);
        run("线程池里写，InheritableThreadLocal", pool, () -> pool.submit(() -> insert(Boolean.TRUE.equals(PT_INH.get()))), true);
        run("线程池里写，提交时包装任务", pool, () -> pool.submit(wrap(() -> insert(Boolean.TRUE.equals(PT.get())))), true);
        pool.shutdown();
    }

    static void run(String name, ExecutorService pool, Runnable handler, boolean async) throws Exception {
        TABLES.clear();
        for (int i = 0; i < 100; i++) {
            PT.set(true); PT_INH.set(true);          // 入口：请求头 x-pt: 1
            try { handler.run(); } finally { PT.remove(); PT_INH.remove(); }
        }
        if (async) { pool.submit(() -> { }).get(); Thread.sleep(200); }
        System.out.printf("%-34s 影子表 %3d 行，真表 %3d 行%n", name,
                TABLES.getOrDefault("t_coupon_user_pt", new AtomicInteger()).get(),
                TABLES.getOrDefault("t_coupon_user", new AtomicInteger()).get());
    }
}
