package labs.algo.hash_table;

import java.util.HashSet;
import java.util.Random;

/**
 * 一天 500 万个单号做幂等：HashSet<Long> 和 long 原语开放寻址各占多少内存、多快。
 * 每种单独起一个 JVM 跑，内存才干净：
 *   java -Xms2g -Xmx2g -cp algo/hash-table/target/classes labs.algo.hash_table.MemBench hashset
 *   java -Xms2g -Xmx2g -cp algo/hash-table/target/classes labs.algo.hash_table.MemBench open
 */
public class MemBench {
    static long used() {
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(100); } catch (InterruptedException e) { } }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    public static void main(String[] args) {
        String kind = args.length > 0 ? args[0] : "open";
        int n = 5_000_000;
        long[] ids = new long[n];
        Random r = new Random(1);
        for (int i = 0; i < n; i++) ids[i] = r.nextLong() & Long.MAX_VALUE;
        long before = used();
        Object keep;
        long t0 = System.nanoTime(), dup = 0;
        if (kind.equals("hashset")) {
            HashSet<Long> s = new HashSet<>();
            for (long id : ids) s.add(id);
            for (long id : ids) if (!s.add(id)) dup++;     // 第二遍全是重复单
            keep = s;
        } else {
            LongOpenHashSet s = new LongOpenHashSet(1 << 4, 0.75, true);
            for (long id : ids) s.add(id);
            for (long id : ids) if (!s.add(id)) dup++;
            keep = s;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long after = used();
        long exact = org.openjdk.jol.info.GraphLayout.parseInstance(keep).totalSize();   // JOL 逐个对象数出来的
        System.out.printf("%-8s 放 %d 个再查重 %d 次：用时 %d ms，JOL 数出 %.0f MB，每个单号 %.1f 字节（Runtime 差值 %.0f MB，只作参考）%n",
                kind, n, dup, ms, exact / 1e6, exact / (double) n, (after - before) / 1e6);
        if (keep.hashCode() == 42) System.out.println();     // 别让 JIT 把集合优化掉
    }
}
