package labs.javase.exception_reflection_proxy;

import java.io.Closeable;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * JDK 17：
 *   java -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true -cp target/classes labs.javase.exception_reflection_proxy.Demo
 * 生成的代理类会存到当前目录的 jdk/proxy1/$Proxy0.class，可以 javap 看。
 */
public class Demo {

    static class Conn implements Closeable {
        final String name;
        Conn(String name) { this.name = name; }
        public void close() { throw new IllegalStateException(name + " 关闭失败"); }
    }

    @SuppressWarnings("finally")
    static String finallyReturns() {
        try { throw new OutOfStockException(88, 100); }
        finally { return "finally 里的 return 吞掉了异常"; }
    }

    /** 业务里当作“控制流”用的异常：不填栈，也不需要栈。 */
    static class NoStack extends RuntimeException {
        NoStack() { super("x", null, false, false); }     // 第 4 个参数 writableStackTrace = false
    }

    static int depth(int n) { return n == 0 ? new Throwable().getStackTrace().length : depth(n - 1); }

    static Exception deep(int n, boolean stack) {
        if (n > 0) return deep(n - 1, stack);
        return stack ? new RuntimeException("x") : new NoStack();
    }

    public static void main(String[] args) throws Exception {
        System.out.println(System.getProperty("java.version"));

        System.out.println("\n== 1. 异常：try-with-resources 和 suppressed ==");
        try (Conn a = new Conn("连接 A"); Conn b = new Conn("连接 B")) {
            throw new OutOfStockException(88, 100);
        } catch (OutOfStockException e) {
            System.out.println("  主异常：" + e.getMessage());
            for (Throwable s : e.getSuppressed()) System.out.println("  被压住的：" + s.getMessage());
        }
        System.out.println("  " + finallyReturns());

        System.out.println("\n== 2. 异常的成本在填栈 ==");
        for (int d : new int[]{10, 100, 1000}) {
            final int dd = d;
            double with = timeNs(() -> deep(dd, true)), without = timeNs(() -> deep(dd, false));
            System.out.printf("  调用深度 %4d：new 一个带栈的异常 %7.0f ns，不填栈的 %6.0f ns%n", d, with, without);
        }
        int lost = -1;
        for (int i = 0; i < 200_000; i++) {
            try { Object o = null; o.hashCode(); }
            catch (NullPointerException e) { if (e.getStackTrace().length == 0) { lost = i; break; } }
        }
        System.out.println("  同一处 NPE 连抛：第 " + lost + " 次起栈轨迹变成空的（JIT 的 OmitStackTraceInFastThrow）");

        System.out.println("\n== 3. 反射：Method.invoke 前 15 次和之后走的不是一条路 ==");
        Method find = OrderServiceImpl.class.getMethod("find", long.class);
        OrderServiceImpl impl = new OrderServiceImpl();
        Method spy = Demo.class.getDeclaredMethod("whoCalledMe");
        String last = null;
        for (int i = 1; i <= 20; i++) {
            String who = (String) spy.invoke(null);
            if (!who.equals(last)) { System.out.println("  第 " + i + " 次 invoke：调用方是 " + who); last = who; }
        }
        try {
            Field table = HashMap.class.getDeclaredField("table");
            table.setAccessible(true);
        } catch (InaccessibleObjectException e) {
            System.out.println("  setAccessible(HashMap.table) → InaccessibleObjectException（JDK 16 起强封装）");
        }
        long n = 20_000_000;
        System.out.printf("  直接调用 %d 次：%.1f ms；Method.invoke：%.1f ms%n", n,
                timeMs(() -> { long s = 0; for (long i = 0; i < n; i++) s += impl.find(i).length(); return s; }),
                timeMs(() -> { long s = 0; for (long i = 0; i < n; i++) s += ((String) find.invoke(impl, i)).length(); return s; }));

        System.out.println("\n== 4. 注解：保留策略决定运行时看不看得见 ==");
        Method findItf = OrderService.class.getMethod("find", long.class);
        System.out.println("  @Audit（RUNTIME）：" + findItf.getAnnotation(Audit.class));
        System.out.println("  @BuildOnly（CLASS）：" + findItf.getAnnotation(BuildOnly.class));
        Audit a = findItf.getAnnotation(Audit.class);
        System.out.println("  读到的注解对象的类：" + a.getClass().getName() + "，是 Proxy 吗？" + Proxy.isProxyClass(a.getClass()));

        System.out.println("\n== 5. 动态代理：手写一个“事务 + 审计” ==");
        OrderService proxy = (OrderService) Proxy.newProxyInstance(
                OrderService.class.getClassLoader(), new Class<?>[]{OrderService.class}, new TxAuditHandler(impl));
        Class<?> pc = proxy.getClass();
        System.out.println("  代理类：" + pc.getName() + "，父类 " + pc.getSuperclass().getName() + "，final=" + Modifier.isFinal(pc.getModifiers())
                + "，实现 " + Arrays.toString(pc.getInterfaces()).replace("interface labs.javase.exception_reflection_proxy.", ""));
        System.out.println("  正常下单 2 件：");
        System.out.println("    → " + proxy.place(1024, 88, 2) + "（place 里 this.find() 没有打印审计：自调用不经过代理）");
        System.out.println("  买 100 件：");
        try { proxy.place(1024, 88, 100); } catch (OutOfStockException e) { System.out.println("    调用方拿到：" + e.getClass().getSimpleName()); }
        System.out.println("  用户 4040 风控超时：");
        try { proxy.place(4040, 88, 1); } catch (RiskCheckTimeoutException e) { System.out.println("    调用方拿到：" + e.getClass().getSimpleName()); }
        try {
            Proxy.newProxyInstance(Demo.class.getClassLoader(), new Class<?>[]{OrderServiceImpl.class}, new TxAuditHandler(impl));
        } catch (IllegalArgumentException e) {
            System.out.println("  拿类去做 JDK 代理 → IllegalArgumentException: " + e.getMessage().replace("labs.javase.exception_reflection_proxy.", ""));
        }
    }

    /** 看看是谁在反射调用我：前 15 次是 native 访问器，之后是生成的字节码访问器。 */
    static String whoCalledMe() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        return st[1].getClassName().replace("jdk.internal.reflect.", "") + "." + st[1].getMethodName();
    }

    interface Task { Object run() throws Exception; }

    static double timeNs(Task t) throws Exception {
        for (int i = 0; i < 20_000; i++) t.run();
        long s = System.nanoTime(); int k = 20_000;
        for (int i = 0; i < k; i++) t.run();
        return (System.nanoTime() - s) / (double) k;
    }

    static double timeMs(Task t) throws Exception {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 4; r++) { long s = System.nanoTime(); t.run(); best = Math.min(best, (System.nanoTime() - s) / 1e6); }
        return best;
    }
}
