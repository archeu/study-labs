package labs.jvm.jvm_classloading;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * 实验 3：SPI 为什么要“反着”加载。
 * 搭两层加载器，模拟“JDK 里的 DriverManager / 框架”在上层、“驱动 / 业务实现”在下层：
 *   framework：只定义 spi 包里的类（PaymentChannel、ChannelRegistry），看不见 META-INF/services
 *   app      ：URLClassLoader(target/classes)，parent = framework，双亲委派
 */
public class SpiDemo {
    static final File CLASSES = new File("target/classes");

    /** 只认 spi 包的上层加载器。 */
    static class FrameworkLoader extends ClassLoader {
        FrameworkLoader() { super(null); }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("labs.jvm.jvm_classloading.spi.")) throw new ClassNotFoundException(name);
            File f = new File(CLASSES, name.replace('.', '/') + ".class");
            try (InputStream in = new java.io.FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                byte[] b = out.toByteArray();
                return defineClass(name, b, 0, b.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
        @Override public String toString() { return "framework"; }
    }

    static class Named extends URLClassLoader {
        final String n;
        Named(String n, ClassLoader parent) throws Exception {
            super(new URL[]{CLASSES.toURI().toURL()}, parent);
            this.n = n;
        }
        @Override public String toString() { return n; }
    }

    public static void main(String[] args) throws Exception {
        FrameworkLoader framework = new FrameworkLoader();
        Named app = new Named("app", framework);
        Named isolated = new Named("isolated", null); // 不委派：自己再加载一份 PaymentChannel

        Class<?> reg = app.loadClass("labs.jvm.jvm_classloading.spi.ChannelRegistry");
        System.out.println("ChannelRegistry 由谁定义：" + reg.getClassLoader());
        System.out.println("MockPay        由谁定义：" + app.loadClass("labs.jvm.jvm_classloading.channel.MockPay").getClassLoader());
        Method own = reg.getMethod("viaOwnLoader");
        Method tccl = reg.getMethod("viaTccl");

        System.out.println("\n① 框架用自己的加载器找实现：" + own.invoke(null));
        run("② TCCL = framework", framework, tccl);
        run("③ TCCL = app       ", app, tccl);
        run("④ TCCL = isolated  ", isolated, tccl);
        app.close();
        isolated.close();
    }

    static void run(String label, ClassLoader cl, Method m) throws Exception {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(cl);
        try {
            System.out.println(label + " → " + m.invoke(null));
        } finally {
            t.setContextClassLoader(old); // 换了 TCCL 一定要还回去
        }
    }
}
