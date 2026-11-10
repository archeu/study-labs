package labs.jvm.jvm_classloading;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * 实验 2：同一个 Order.class，两个不委派的加载器各加载一次。
 * 类名一样、字节一样，JVM 却认为是三个不同的类（再加上 app 加载器那份）。
 */
public class TwoOrders {
    public static void main(String[] args) throws Exception {
        URL classes = new File("target/classes").toURI().toURL();
        // parent = null：只问启动类加载器，不问 app 加载器，相当于“各管各的”
        URLClassLoader a = new URLClassLoader(new URL[]{classes}, null);
        URLClassLoader b = new URLClassLoader(new URL[]{classes}, null);
        String n = "labs.jvm.jvm_classloading.Order";

        Class<?> ca = a.loadClass(n);
        Class<?> cb = b.loadClass(n);
        System.out.println("ca.getName().equals(cb.getName()) = " + ca.getName().equals(cb.getName()));
        System.out.println("ca == cb                           = " + (ca == cb));
        System.out.println("ca == Order.class                  = " + (ca == Order.class));

        Object fromA = ca.getConstructor(long.class, int.class).newInstance(1024L, 2);
        System.out.println("fromA instanceof Order             = " + (fromA instanceof Order));
        try {
            Order o = (Order) fromA;
            System.out.println("转换成功？" + o);
        } catch (ClassCastException e) {
            System.out.println("\nClassCastException: " + e.getMessage());
        }
        a.close();
        b.close();
    }
}
