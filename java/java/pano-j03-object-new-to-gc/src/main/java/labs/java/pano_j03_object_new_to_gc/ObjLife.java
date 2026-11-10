package labs.java.pano_j03_object_new_to_gc;

import java.lang.management.*;
import java.util.*;
import org.openjdk.jol.info.ClassLayout;

/**
 * 全景「一个对象从 new 到被回收」的实测：让订单对象在 G1 上走完一生。
 * 建议参数见 README：-Xms256m -Xmx256m -Xlog:gc*:file=gc.log -XX:+HeapDumpOnOutOfMemoryError
 *   java ... ObjLife            走完 1～9 站，最后故意泄漏到 OOM
 *   java ... ObjLife noleak     只走到第 7 站，正常结束
 */
public class ObjLife {
    static final int MB = 1 << 20;
    static final List<Order> LEAK = new ArrayList<>();          // 第 8 站：只放不删的 static 集合
    static final Order[] ring = new Order[1024];                 // 让短命订单“逃逸”，免得被 JIT 优化掉
    static long sink;

    public static void main(String[] args) throws Exception {
        boolean leak = !(args.length > 0 && args[0].equals("noleak"));
        System.out.printf("JDK %s，最大堆 %d MB，GC：%s%n", Runtime.version(), Runtime.getRuntime().maxMemory() / MB, gcNames());

        // ① 类加载
        Order first = new Order(9527, 1024, 88, 2, 59800, "CREATED");
        System.out.println("① Order 由谁加载：" + Order.class.getClassLoader().getName() + "；String 由谁加载：" + String.class.getClassLoader() + "（null = 启动类加载器）");

        // ③ 对象长什么样
        System.out.println("③ 一个 Order 的内存布局：");
        System.out.println(ClassLayout.parseInstance(first).toPrintable());

        // ② JIT 与逃逸分析：循环里的临时对象到底进没进堆
        long a0 = allocated();
        long sum = 0;
        for (int i = 0; i < 50_000_000; i++) sum += priceOf(i);
        long temp = allocated() - a0;
        System.out.printf("② 循环里 new 了 5000 万个临时 Money：这个线程实际在堆上分配了 %.1f MB（结果 %d）%n", temp / (double) MB, sum);

        // ⑤ 短命对象：Young GC
        GcSnap s0 = snap();
        for (long i = 0; i < 20_000_000L; i++) ring[(int) (i & 1023)] = new Order(i, 1024, 88, 2, 59800, "CREATED");
        report("⑤ new 了 2000 万个短命订单（约 915 MB）", s0);

        // ⑥ 长命对象：晋升
        long old0 = poolUsed("Old");
        Map<Long, Order> cache = new HashMap<>();
        s0 = snap();
        for (long i = 0; i < 1_000_000L; i++) cache.put(i, new Order(i, 1024, 88, 2, 59800, "PAID"));
        for (long i = 0; i < 6_000_000L; i++) ring[(int) (i & 1023)] = new Order(i, 1, 1, 1, 1, "X");   // 再触发几次 Young GC，让缓存里的订单熬到晋升
        report("⑥ 把 100 万个订单放进缓存，再过几次 Young GC", s0);
        System.out.printf("   老年代：%d MB → %d MB%n", old0 / MB, poolUsed("Old") / MB);

        // ④ 大对象
        s0 = snap();
        byte[][] keep = new byte[4][];
        for (int i = 0; i < 40; i++) { keep[i & 3] = new byte[2 * MB]; sink += keep[i & 3].length; }
        report("④ 分配了 40 个 2 MB 的 byte[]（导出文件的缓冲区）", s0);

        // ⑦ 缓存清掉：订单不可达 → 并发标记 → Mixed GC
        long oldBefore = poolUsed("Old");
        cache.clear(); cache = null; keep = null;
        s0 = snap();
        for (int round = 0; round < 6; round++) {
            for (long i = 0; i < 6_000_000L; i++) ring[(int) (i & 1023)] = new Order(i, 1, 1, 1, 1, "X");
            Thread.sleep(300);                                   // 给并发标记线程一点时间
        }
        report("⑦ 清空缓存后继续下单", s0);
        System.out.printf("   老年代：%d MB → %d MB%n", oldBefore / MB, poolUsed("Old") / MB);

        if (!leak) return;
        // ⑧⑨ 泄漏：static 集合只放不删
        System.out.println("⑧ 开始泄漏：每个订单都放进 static 的 LEAK …");
        s0 = snap();
        try {
            for (long i = 0; ; i++) {
                LEAK.add(new Order(i, 1024, 88, 2, 59800, "PAID"));
                if (i == 2_500_000L) {                              // 留一个窗口，外面用 jcmd 看直方图
                    System.out.println("   PAUSE pid=" + ProcessHandle.current().pid() + "，LEAK 里有 250 万个订单，老年代 " + poolUsed("Old") / MB + " MB");
                    Thread.sleep(4000);
                }
            }
        } catch (OutOfMemoryError e) {
            int n = LEAK.size();
            LEAK.clear();
            report("⑨ " + e + "，这时 LEAK 里有 " + n + " 个订单", s0);
        }
    }

    record Money(long cents) {}
    static long priceOf(int i) { Money m = new Money(i & 0xff); return m.cents(); }   // m 不逃出这个方法

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }
    record GcSnap(Map<String, long[]> m) {}
    static GcSnap snap() {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) m.put(g.getName(), new long[]{g.getCollectionCount(), g.getCollectionTime()});
        return new GcSnap(m);
    }
    static void report(String label, GcSnap before) {
        StringBuilder sb = new StringBuilder(label).append("：");
        GcSnap now = snap();
        now.m().forEach((k, v) -> { long[] b = before.m().get(k); if (v[0] - b[0] > 0) sb.append(String.format("%s %d 次共 %d ms；", k, v[0] - b[0], v[1] - b[1])); });
        System.out.println(sb);
    }
    static long poolUsed(String key) {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) if (p.getName().contains(key)) return p.getUsage().getUsed();
        return -1;
    }
    static String gcNames() {
        List<String> l = new ArrayList<>();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) l.add(g.getName());
        return String.join(" / ", l);
    }
}
