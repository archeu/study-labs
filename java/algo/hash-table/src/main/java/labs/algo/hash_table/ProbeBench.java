package labs.algo.hash_table;

import java.util.Random;

/**
 * 负载因子 α 越高，线性探测要探几次？和 Knuth 的公式对一下。
 * 再看雪花 ID（低 12 位序列号在低流量时几乎全是 0）不打散时会怎样。
 */
public class ProbeBench {
    public static void main(String[] args) {
        int cap = 1 << 20;
        Random r = new Random(7);
        System.out.println("α      命中平均探测(实测/公式)    未命中平均探测(实测/公式)");
        for (double a : new double[]{0.25, 0.5, 0.75, 0.9, 0.95}) {
            LongOpenHashSet s = new LongOpenHashSet(cap, 0.99, true);
            int n = (int) (a * cap);
            long[] in = new long[n];
            for (int i = 0; i < n; i++) { in[i] = r.nextLong(); s.add(in[i]); }
            s.probes = 0;
            for (long k : in) s.contains(k);
            double hit = s.probes / (double) n;
            s.probes = 0;
            int m = 200_000;
            for (int i = 0; i < m; i++) s.contains(r.nextLong());
            double miss = s.probes / (double) m;
            double fHit = 0.5 * (1 + 1 / (1 - a)), fMiss = 0.5 * (1 + 1 / ((1 - a) * (1 - a)));
            System.out.printf("%.2f   %6.2f / %6.2f              %8.2f / %8.2f%n", a, hit, fHit, miss, fMiss);
        }

        System.out.println();
        // 雪花 ID：时间戳(41 位) << 22 | 机器(10 位) << 12 | 序列号(12 位)。低流量时每毫秒 1 单，序列号都是 0。
        int n = 20_000;
        long[] ids = new long[n];
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < n; i++) ids[i] = ((t0 + i) << 22) | (5L << 12);
        for (boolean mix : new boolean[]{false, true}) {
            LongOpenHashSet s = new LongOpenHashSet(1 << 19, 0.75, mix);
            long st = System.nanoTime();
            for (long id : ids) s.add(id);
            long ms = (System.nanoTime() - st) / 1_000_000;
            System.out.printf("雪花 ID %d 个，%s：平均每次 add 探测 %.1f 次，用时 %d ms%n",
                    n, mix ? "先 fmix64 打散" : "直接 key & mask", s.probes / (double) n, ms);
        }
        java.util.HashSet<Long> hs = new java.util.HashSet<>();
        long st = System.nanoTime();
        for (long id : ids) hs.add(id);
        System.out.printf("同一批 ID 放进 HashSet<Long>：用时 %d ms（Long.hashCode 是高低 32 位异或，HashMap 再做一次 h ^ h>>>16）%n",
                (System.nanoTime() - st) / 1_000_000);
    }
}
