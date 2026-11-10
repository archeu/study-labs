package labs.jvm.jvm_classloading;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验 4：类加载器泄漏 → 元空间涨。
 * 模拟“反复热部署”：每轮新建一个加载器，加载一份 Order 和 Order 的若干代理类。
 *   -Dkeep=true ：把每轮的对象留在一个 static 列表里（像 ThreadLocal / 缓存 / 注册表忘了清）
 *   -Dkeep=false：用完就丢
 * 用 -XX:MaxMetaspaceSize=32m -Xlog:class+unload 跑，对比两种结局。
 */
public class LoaderLeak {
    static final List<Object> HOLD = new ArrayList<Object>();

    public static void main(String[] args) throws Exception {
        boolean keep = Boolean.getBoolean("keep");
        int rounds = Integer.getInteger("rounds", 3000);
        URL classes = new File("target/classes").toURI().toURL();
        for (int i = 1; i <= rounds; i++) {
            URLClassLoader l = new URLClassLoader(new URL[]{classes}, null);
            Class<?> c = l.loadClass("labs.jvm.jvm_classloading.Order");
            Object o = c.getConstructor(long.class, int.class).newInstance((long) i, 1);
            // 每轮再生成几个动态代理类，模拟框架为每个“部署”生成的类
            for (Class<?> itf : new Class<?>[]{Runnable.class, Comparable.class, java.util.concurrent.Callable.class}) {
                Object p = java.lang.reflect.Proxy.newProxyInstance(l, new Class<?>[]{itf}, new java.lang.reflect.InvocationHandler() { public Object invoke(Object px, java.lang.reflect.Method m, Object[] a) { return null; } });
                if (keep) HOLD.add(p);
            }
            if (keep) HOLD.add(o);           // 一个实例 → 它的 Class → 它的加载器 → 加载器里的全部类
            l.close();
            if (i % 500 == 0) {
                java.lang.management.MemoryPoolMXBean meta = null;
                for (java.lang.management.MemoryPoolMXBean b : java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
                    if (b.getName().equals("Metaspace")) meta = b;
                System.out.printf("第 %d 轮  元空间已用 %.1f MB  已加载类 %d  已卸载类 %d%n", i,
                        meta.getUsage().getUsed() / 1048576.0,
                        java.lang.management.ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                        java.lang.management.ManagementFactory.getClassLoadingMXBean().getUnloadedClassCount());
            }
        }
        System.out.println("跑完了，没有 OOM");
    }
}
