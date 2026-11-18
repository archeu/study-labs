package labs.algo.heap_priority_queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;

/**
 * 粗测：簿里已有 N 张单，之后每一步“挂一张新单 + 取走最优价那张”，簿的大小不变。
 * 四种做法：无序 ArrayList（取最优要扫一遍）、有序 ArrayList（二分找位置再插，要搬后半段）、
 * PriorityQueue（二叉堆）、TreeMap<价格, 张数>（红黑树）。输出每步纳秒数，只看量级和比例。
 * 直接起 JVM 跑：java -Xms1g -Xmx1g -cp target/classes labs.algo.heap_priority_queue.HeapBench
 */
public class HeapBench {
    interface Book { void offer(long p); long pollMin(); }

    static final class Unsorted implements Book {
        final ArrayList<Long> a = new ArrayList<>();
        public void offer(long p) { a.add(p); }
        public long pollMin() {
            int best = 0;
            for (int i = 1; i < a.size(); i++) if (a.get(i) < a.get(best)) best = i;
            long v = a.get(best);
            a.set(best, a.get(a.size() - 1));       // 拿末尾填坑，O(1) 删除
            a.remove(a.size() - 1);
            return v;
        }
    }
    static final class Sorted implements Book {      // 降序存，最小的在末尾，取走 O(1)
        final ArrayList<Long> a = new ArrayList<>();
        public void offer(long p) {
            int i = Collections.binarySearch(a, p, Collections.reverseOrder());
            if (i < 0) i = -i - 1;
            a.add(i, p);                             // System.arraycopy 搬后半段
        }
        public long pollMin() { return a.remove(a.size() - 1); }
    }
    static final class Heap implements Book {
        final PriorityQueue<Long> q = new PriorityQueue<>();
        public void offer(long p) { q.offer(p); }
        public long pollMin() { return q.poll(); }
    }
    static final class Tree implements Book {
        final TreeMap<Long, Integer> m = new TreeMap<>();
        public void offer(long p) { m.merge(p, 1, Integer::sum); }
        public long pollMin() {
            Long k = m.firstKey();
            if (m.merge(k, -1, Integer::sum) == 0) m.remove(k);
            return k;
        }
    }

    static double run(String kind, int n, int ops, long seed) {
        Book b;
        switch (kind) {
            case "unsorted": b = new Unsorted(); break;
            case "sorted":   b = new Sorted(); break;
            case "heap":     b = new Heap(); break;
            default:         b = new Tree();
        }
        Random r = new Random(seed);
        ArrayList<Long> init = new ArrayList<>(n);
        for (int i = 0; i < n; i++) init.add(10_000L + r.nextInt(2_000));  // 2000 个价位
        if (b instanceof Sorted) { init.sort(Collections.reverseOrder()); ((Sorted) b).a.addAll(init); }
        else if (b instanceof Unsorted) ((Unsorted) b).a.addAll(init);
        else for (Long p : init) b.offer(p);                                  // 预填不计时
        long sink = 0, t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) { b.offer(10_000 + r.nextInt(2_000)); sink += b.pollMin(); }
        long t = System.nanoTime() - t0;
        if (sink == 42) System.out.println();
        return (double) t / ops;
    }

    public static void main(String[] args) {
        String[] kinds = {"unsorted", "sorted", "heap", "tree"};
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        for (String k : kinds) run(k, 10_000, 20_000, 1);            // 预热
        System.out.printf("%-10s", "N");
        for (String k : kinds) System.out.printf("%14s", k);
        System.out.println("   (ns / 步：挂一张 + 取最优一张)");
        for (int n : sizes) {
            System.out.printf("%-10d", n);
            for (String k : kinds) {
                boolean linear = k.equals("unsorted") || k.equals("sorted");
                int ops = linear ? Math.max(200, (int) (40_000_000L / n)) : 200_000;
                if (k.equals("unsorted")) ops = Math.max(100, ops / 4);
                System.out.printf("%14.0f", run(k, n, ops, 7));
            }
            System.out.println();
        }
    }
}
