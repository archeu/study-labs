package labs.jvm.jvm_object_new_layout;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

/**
 * ⑤ 亲眼看到：一个 Order 在堆里到底长什么样、占多少字节。
 * 运行：mvn -q compile exec:java -Dexec.mainClass=labs.jvm.jvm_object_new_layout.Layout
 * 换参数看变化：MAVEN_OPTS="-XX:-UseCompressedOops" 再跑一次
 */
public class Layout {
    public static void main(String[] args) {
        System.out.println(VM.current().details());

        Order o = new Order(1001L, 1024L, 88, 2, 17600L, "PAID");
        System.out.println(ClassLayout.parseInstance(o).toPrintable());

        // 算过 hashCode 以后，哈希码被写进 mark word
        System.out.println("identityHashCode = " + Integer.toHexString(System.identityHashCode(o)));
        System.out.println(ClassLayout.parseInstance(o).toPrintable());

        System.out.println(ClassLayout.parseInstance(new Object()).toPrintable());
        System.out.println(ClassLayout.parseInstance(Long.valueOf(1024L)).toPrintable());
        System.out.println(ClassLayout.parseInstance(new int[3]).toPrintable());
        System.out.println(ClassLayout.parseInstance(new OrderLite(1001L, 1024, 88, (short) 2, 17600, (byte) 1)).toPrintable());

        // 一个 Order 连同它引用的 String、byte[] 一共多大
        System.out.println(GraphLayout.parseInstance(o).toFootprint());
    }
}
