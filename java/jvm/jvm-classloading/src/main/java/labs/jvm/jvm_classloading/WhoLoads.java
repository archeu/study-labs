package labs.jvm.jvm_classloading;

/**
 * 实验 1：谁加载了谁。打印加载器链，以及几个类各由哪个加载器定义。
 * 加 -Xlog:class+load 跑，可以看到 Order 是在第一次 new 时才加载的。
 */
public class WhoLoads {
    public static void main(String[] args) throws Exception {
        ClassLoader app = WhoLoads.class.getClassLoader();
        System.out.println("加载器链（从下往上）：");
        for (ClassLoader l = app; ; l = l.getParent()) {
            System.out.println("  " + (l == null ? "null = 启动类加载器（C++ 实现，Java 里拿不到对象）" : l));
            if (l == null) break;
        }
        System.out.println("\n各类由谁定义：");
        show(String.class);
        show(java.sql.DriverManager.class);
        show(WhoLoads.class);
        System.out.println("\n准备 new Order ……");
        Order o = new Order(9527, 2);
        System.out.println("  得到 " + o);
        show(Order.class);
        System.out.println("\n当前线程的上下文类加载器（TCCL）：" + Thread.currentThread().getContextClassLoader());
    }

    static void show(Class<?> c) {
        System.out.printf("  %-26s → %s%n", c.getSimpleName(), c.getClassLoader());
    }
}
