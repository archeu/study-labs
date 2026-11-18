package labs.algo.red_black_tree;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 1 真实 TreeMap 的树高：价位一路上涨插 100 万个（要 --add-opens java.base/java.util=ALL-UNNAMED）
 * 2 撤单多的盘口：PriorityQueue 按价位撤（remove(Object) 线性） vs TreeMap
 * 3 几个坑：同价覆盖、compareTo 和 equals 不一致、遍历视图时改原 map
 * java -Xmx1g --add-opens java.base/java.util=ALL-UNNAMED -cp algo/red-black-tree/target/classes labs.algo.red_black_tree.BookBench
 */
public class BookBench {
    public static void main(String[] args) throws Exception {
        heights();
        cancelBench();
        pitfalls();
    }

    static void heights() throws Exception {
        System.out.println("== 真实 TreeMap 的树高");
        for (int n : new int[]{1_000, 1_000_000}) {
            TreeMap<Long, Integer> m = new TreeMap<>();
            for (int i = 0; i < n; i++) m.put(10_000L + i, 1);              // 一路上涨
            TreeMap<Long, Integer> r = new TreeMap<>();
            Random rnd = new Random(1);
            while (r.size() < n) r.put(rnd.nextLong(), 1);                 // 随机
            System.out.printf("n=%,d：一路上涨 树高 %d，随机 树高 %d，上界 2·log₂(n+1) = %.1f，log₂n = %.1f%n",
                    n, height(m), height(r), 2 * log2(n + 1), log2(n));
        }
        System.out.println();
    }

    static double log2(double x) { return Math.log(x) / Math.log(2); }

    static int height(TreeMap<?, ?> m) throws Exception {
        Field root = TreeMap.class.getDeclaredField("root");
        root.setAccessible(true);
        Object r = root.get(m);
        if (r == null) return 0;
        Class<?> entry = r.getClass();
        Field left = entry.getDeclaredField("left"), right = entry.getDeclaredField("right");
        left.setAccessible(true); right.setAccessible(true);
        // 层序数层数，免得递归
        List<Object> level = new ArrayList<>(); level.add(r); int h = 0;
        while (!level.isEmpty()) {
            h++;
            List<Object> next = new ArrayList<>();
            for (Object e : level) { Object a = left.get(e), b = right.get(e); if (a != null) next.add(a); if (b != null) next.add(b); }
            level = next;
        }
        return h;
    }

    /** 盘口 5 万个价位，随机撤掉其中 2 万个整档，再看最优价。 */
    static void cancelBench() {
        System.out.println("== 撤单：PriorityQueue vs TreeMap（5 万个价位，撤 2 万档）");
        int n = 50_000, c = 20_000;
        Random r = new Random(2);
        long[] prices = new long[n];
        for (int i = 0; i < n; i++) prices[i] = 10_000 + i * 5L;
        long[] cancels = new long[c];
        for (int i = 0; i < c; i++) cancels[i] = prices[r.nextInt(n)];
        for (int round = 1; round <= 2; round++) {
            PriorityQueue<Long> pq = new PriorityQueue<>();
            for (long p : prices) pq.add(p);
            long t0 = System.nanoTime();
            for (long p : cancels) pq.remove(p);                    // remove(Object)：先线性找到它
            long tp = System.nanoTime() - t0;
            TreeMap<Long, Integer> tm = new TreeMap<>();
            for (long p : prices) tm.put(p, 1);
            t0 = System.nanoTime();
            for (long p : cancels) tm.remove(p);
            long tt = System.nanoTime() - t0;
            System.out.printf("第 %d 轮：PriorityQueue %d ms，TreeMap %.1f ms，最优价一致 %s%n",
                    round, tp / 1_000_000, tt / 1e6, pq.peek().equals(tm.firstKey()));
        }
        System.out.println();
    }

    static void pitfalls() {
        System.out.println("== 坑");
        TreeMap<Long, String> byPrice = new TreeMap<>();
        byPrice.put(10050L, "#2 张三 1 手"); byPrice.put(10050L, "#4 李四 2 手");
        System.out.println("坑 1 同价直接当 key：" + byPrice + "  ← #2 被覆盖了");

        TreeSet<BigDecimal> ts = new TreeSet<>(); HashSet<BigDecimal> hs = new HashSet<>();
        for (BigDecimal d : new BigDecimal[]{new BigDecimal("100.5"), new BigDecimal("100.50")}) { ts.add(d); hs.add(d); }
        System.out.println("坑 2 BigDecimal 100.5 和 100.50：TreeSet 大小 " + ts.size() + "，HashSet 大小 " + hs.size());

        TreeMap<Long, Integer> m = new TreeMap<>();
        for (long p = 10000; p < 10010; p++) m.put(p, 1);
        try {
            for (Map.Entry<Long, Integer> e : m.headMap(10005L).entrySet()) if (e.getKey() == 10002L) m.remove(10003L);
            System.out.println("坑 3 没抛");
        } catch (ConcurrentModificationException e) {
            System.out.println("坑 3 遍历 headMap 视图时删原 map：ConcurrentModificationException");
        }
    }
}
