import java.io.*;
import java.lang.management.*;
import java.nio.ByteBuffer;
import java.util.*;

/** 看 JVM 的内存分成哪几块，再把其中几块撑爆。JDK 8 / 17 / 21 都能跑。
 *  java MemDemo pools | heap | meta | direct | stack */
public class MemDemo {
    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "pools";
        System.out.println("Java " + System.getProperty("java.version") + "，模式 " + mode);
        switch (mode) {
            case "pools":  pools();  break;
            case "heap":   heap();   break;
            case "meta":   meta();   break;
            case "direct": direct(); break;
            case "stack":  stack();  break;
            default: System.out.println("未知模式");
        }
    }

    // 1. 列出所有内存池：哪些在堆里，哪些不在
    static void pools() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = p.getUsage();
            System.out.printf("%-8s %-32s 已用 %6.1f MB  已提交 %6.1f MB  上限 %s%n",
                    p.getType() == MemoryType.HEAP ? "[堆]" : "[非堆]", p.getName(),
                    mb(u.getUsed()), mb(u.getCommitted()), u.getMax() < 0 ? "不限" : String.format("%.0f MB", mb(u.getMax())));
        }
        System.out.printf("堆最大值 Runtime.maxMemory() = %.0f MB%n", mb(Runtime.getRuntime().maxMemory()));
        com.sun.management.HotSpotDiagnosticMXBean hs =
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        for (String f : new String[]{"UseG1GC", "UseCompressedOops", "UseCompressedClassPointers", "ThreadStackSize",
                "MaxMetaspaceSize", "CompressedClassSpaceSize", "ReservedCodeCacheSize", "MaxDirectMemorySize", "MaxTenuringThreshold"})
            System.out.println("  -XX:" + f + " = " + hs.getVMOption(f).getValue());
    }

    // 2. 堆：一直 new 1 MB 的数组并留着
    static void heap() {
        List<byte[]> keep = new ArrayList<>();
        try { while (true) keep.add(new byte[1 << 20]); }
        catch (OutOfMemoryError e) { int n = keep.size(); keep = null; System.out.println("留住了 " + n + " MB 后：" + e); }
    }

    // 3. 元空间：每次用一个新的类加载器，把同一个类再定义一遍（每次都是一个新类）
    static class Dummy { int x; void hello() { } }
    static class OneShotLoader extends ClassLoader {
        OneShotLoader() { super(null); }
        Class<?> define(byte[] b) { return defineClass(Dummy.class.getName(), b, 0, b.length); }
    }
    static void meta() throws IOException {
        byte[] bytes;
        try (InputStream in = MemDemo.class.getResourceAsStream("MemDemo$Dummy.class")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            bytes = out.toByteArray();
        }
        List<Class<?>> keep = new ArrayList<>();
        try { while (true) keep.add(new OneShotLoader().define(bytes)); }
        catch (OutOfMemoryError e) { int n = keep.size(); keep = null; System.out.println("定义了 " + n + " 个类后：" + e); }
    }

    // 4. 直接内存：一直 allocateDirect 1 MB 并留着
    static void direct() {
        List<ByteBuffer> keep = new ArrayList<>();
        try { while (true) keep.add(ByteBuffer.allocateDirect(1 << 20)); }
        catch (OutOfMemoryError e) { int n = keep.size(); keep = null; System.out.println("分配了 " + n + " MB 直接内存后：" + e); }
    }

    // 5. 栈：无限递归，看能递归多深
    static int depth;
    static void down() { depth++; down(); }
    static void stack() {
        try { down(); } catch (StackOverflowError e) { System.out.println("递归 " + depth + " 层后：" + e); }
    }

    static double mb(long b) { return b / 1024.0 / 1024.0; }
}
