package labs.algo.bplus_skiplist;

import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 1 B+ 树：100 万笔成交时间，扇出从 4 到 1200，树高和“查 5 分钟读几个节点”
 * 2 跳表：100 万个用户的成交额，平均每次查找比较几次，层数分布
 * 3 单线程下 TreeMap 和 ConcurrentSkipListMap 的 put/get 耗时
 * java -Xmx2g -cp algo/bplus-skiplist/target/classes labs.algo.bplus_skiplist.IndexBench
 */
public class IndexBench {
    public static void main(String[] args) {
        int n = 1_000_000;
        long[] t = new long[n];
        long cur = 9 * 3_600_000L;                                    // 毫秒，09:00 开始
        Random r = new Random(5);
        for (int i = 0; i < n; i++) { cur += 1 + r.nextInt(30); t[i] = cur; }   // 平均 15.5 ms 一笔
        long from = 13 * 3_600_000L, to = from + 5 * 60_000L;         // 13:00～13:05
        System.out.println("== B+ 树：100 万笔，查 13:00～13:05");
        for (int order : new int[]{4, 16, 128, 1200}) {
            BPlusTree b = new BPlusTree(order);
            for (long x : t) b.insert(x);
            int c = b.rangeCount(from, to);
            System.out.printf("每节点最多 %4d 个 key：树高 %2d，范围内 %,d 笔，读了 %,d 个节点%n", order, b.height(), c, b.nodesVisited);
        }

        System.out.println("\n== 跳表：100 万个用户的成交额，p = 1/4");
        SkipList z = new SkipList(7);
        for (int i = 0; i < n; i++) z.insert(r.nextInt(1_000_000_000), "u" + i);
        int[] cnt = new int[33];
        for (SkipList.Node x = z.head.next[0]; x != null; x = x.next[0]) cnt[x.next.length]++;
        StringBuilder sb = new StringBuilder();
        long ptrs = 0;
        for (int l = 1; l <= z.level; l++) { if (l <= 6) sb.append(l).append(" 层 ").append(String.format("%.2f%%", 100.0 * cnt[l] / n)).append("  "); ptrs += (long) l * cnt[l]; }
        System.out.println("层数分布：" + sb + "…… 最高 " + z.level + " 层；平均每个节点 " + String.format("%.2f", ptrs / (double) n) + " 根前进指针");
        z.compares = 0;
        int q = 100_000;
        for (int i = 0; i < q; i++) z.ceiling(r.nextInt(1_000_000_000));
        System.out.printf("每次查找平均比较 %.1f 次（log₂n = %.1f）%n", z.compares / (double) q, Math.log(n) / Math.log(2));

        System.out.println("\n== 单线程 put / get：TreeMap vs ConcurrentSkipListMap（100 万个 long）");
        for (int round = 1; round <= 3; round++) {
            TreeMap<Long, Long> tm = new TreeMap<>(); ConcurrentSkipListMap<Long, Long> sl = new ConcurrentSkipListMap<>();
            long t0 = System.nanoTime(); for (long x : t) tm.put(x, x); long p1 = System.nanoTime() - t0;
            t0 = System.nanoTime(); for (long x : t) sl.put(x, x); long p2 = System.nanoTime() - t0;
            long s = 0; t0 = System.nanoTime(); for (int i = 0; i < n; i += 3) s += tm.get(t[i]); long g1 = System.nanoTime() - t0;
            t0 = System.nanoTime(); for (int i = 0; i < n; i += 3) s += sl.get(t[i]); long g2 = System.nanoTime() - t0;
            System.out.printf("第 %d 轮：put TreeMap %d ms，CSLM %d ms；get TreeMap %d ms，CSLM %d ms %s%n",
                    round, p1 / 1_000_000, p2 / 1_000_000, g1 / 1_000_000, g2 / 1_000_000, s == 42 ? "" : "");
        }
    }
}
