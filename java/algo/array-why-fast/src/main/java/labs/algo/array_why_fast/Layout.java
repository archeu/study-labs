package labs.algo.array_why_fast;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 数组在 JVM 里长什么样：对象头 + 连续的元素。用 JOL 看布局、量内存。
 * 运行：mvn -q -pl algo/array-why-fast compile exec:java -Dexec.mainClass=labs.algo.array_why_fast.Layout
 */
public class Layout {
    public static void main(String[] args) {
        System.out.println("== 1 int[4] 的布局 ==");
        System.out.println(ClassLayout.parseInstance(new int[]{10, 20, 30, 40}).toPrintable());

        System.out.println("== 2 一个 Integer 对象 ==");
        System.out.println(ClassLayout.parseInstance(Integer.valueOf(1000)).toPrintable());

        int n = 1_000_000;
        int[] arr = new int[n];
        List<Integer> al = new ArrayList<>(n);
        List<Integer> ll = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            arr[i] = i + 1000;             // 避开 -128..127 的 Integer 缓存，每个都是新对象
            al.add(i + 1000);
            ll.add(i + 1000);
        }
        System.out.println("== 3 存 100 万个整数，一共占多少字节（含引用到的所有对象）==");
        System.out.printf("int[]               %,12d%n", GraphLayout.parseInstance(arr).totalSize());
        System.out.printf("ArrayList<Integer>  %,12d%n", GraphLayout.parseInstance(al).totalSize());
        System.out.printf("LinkedList<Integer> %,12d%n", GraphLayout.parseInstance(ll).totalSize());
    }
}
