package labs.algo.bit_ops;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * 风控要记“今天哪些账户下过单”：账户号 0 ~ 1 亿。三种存法比内存和速度。
 * 运行：java -Xmx4g labs.algo.bit_ops.AccountBits
 * 内存用 Runtime 前后差估算，会有几 MB 误差，只看量级。
 */
public class AccountBits {
    static long used() {
        for (int i = 0; i < 3; i++) System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    public static void main(String[] args) {
        int N = 100_000_000;          // 账户号范围
        int active = 10_000_000;      // 今天下过单的账户
        Random r = new Random(1);
        int[] ids = new int[active];
        for (int i = 0; i < active; i++) ids[i] = r.nextInt(N);

        long m0 = used();
        BitSet bits = new BitSet(N);
        long t0 = System.nanoTime();
        for (int id : ids) bits.set(id);
        long t1 = System.nanoTime();
        long m1 = used();
        System.out.printf("BitSet     ：%6.1f MB，写 1000 万次 %4d ms，记下 %d 个账户%n", (m1 - m0) / 1e6, (t1 - t0) / 1000000, bits.cardinality());

        boolean[] flags = new boolean[N];
        t0 = System.nanoTime();
        for (int id : ids) flags[id] = true;
        t1 = System.nanoTime();
        long m2 = used();
        System.out.printf("boolean[]  ：%6.1f MB，写 1000 万次 %4d ms%n", (m2 - m1) / 1e6, (t1 - t0) / 1000000);
        flags = null;
        long m3 = used();

        Set<Long> set = new HashSet<Long>();
        t0 = System.nanoTime();
        for (int id : ids) set.add((long) id);
        t1 = System.nanoTime();
        long m4 = used();
        System.out.printf("HashSet<Long>：%6.1f MB，写 1000 万次 %4d ms，记下 %d 个（只存下过单的）%n", (m4 - m3) / 1e6, (t1 - t0) / 1000000, set.size());

        // 查询：再随机问 1000 万次“这个账户今天下过单吗”
        int[] q = new int[active];
        for (int i = 0; i < active; i++) q[i] = r.nextInt(N);
        for (int round = 1; round <= 2; round++) {
            long h1 = 0, h2 = 0;
            t0 = System.nanoTime();
            for (int id : q) if (bits.get(id)) h1++;
            t1 = System.nanoTime();
            for (int id : q) if (set.contains((long) id)) h2++;
            long t2 = System.nanoTime();
            System.out.printf("第 %d 轮查询 1000 万次：BitSet %d ms，HashSet %d ms（命中 %d / %d）%n", round, (t1 - t0) / 1000000, (t2 - t1) / 1000000, h1, h2);
        }
    }
}
