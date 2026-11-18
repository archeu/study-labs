package labs.algo.linked_list;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * 两段粗测，只看比值：
 *   1. 撤单：n 张单按随机顺序全部撤掉。ArrayList.remove(Object)、LinkedList.remove(Object)、OrderQueue.cancel(id)。
 *   2. 遍历 LinkedList：for (i) get(i) 和迭代器。
 * 每个点热身 2 次，再跑 3 次取最快。直接起 JVM 跑，别在 Maven 进程里跑。
 */
public class CancelBench {

    static volatile long sink;

    public static void main(String[] args) {
        int[] ns = {5_000, 10_000, 20_000, 40_000};
        System.out.println("== 1 撤掉 n 张单（随机顺序），总耗时 ms ==");
        System.out.println("      n   ArrayList.remove(o)   LinkedList.remove(o)   OrderQueue.cancel(id)");
        double[] prev = null;
        for (int n : ns) {
            List<Long> ids = new ArrayList<>(n);
            for (long i = 0; i < n; i++) ids.add(i);
            List<Long> order = new ArrayList<>(ids);
            Collections.shuffle(order, new Random(42));

            double a = best(() -> cancelArrayList(ids, order));
            double l = best(() -> cancelLinkedList(ids, order));
            double q = best(() -> cancelOrderQueue(ids, order));
            System.out.printf("%7d   %10.1f %-8s %10.1f %-9s %10.2f %s%n", n,
                    a, ratio(prev, 0, a), l, ratio(prev, 1, l), q, ratio(prev, 2, q));
            prev = new double[]{a, l, q};
        }

        System.out.println();
        System.out.println("== 2 把 LinkedList 从头到尾读一遍，ms ==");
        System.out.println("      n   for (i) get(i)       迭代器");
        prev = null;
        for (int n : ns) {
            LinkedList<Long> list = new LinkedList<>();
            for (long i = 0; i < n; i++) list.add(i);
            double g = best(() -> sumByIndex(list));
            double it = best(() -> sumByIterator(list));
            System.out.printf("%7d   %10.1f %-8s %8.3f %s%n", n, g, ratio(prev, 0, g), it, ratio(prev, 1, it));
            prev = new double[]{g, it};
        }
    }

    static void cancelArrayList(List<Long> ids, List<Long> order) {
        List<Long> book = new ArrayList<>(ids);
        for (Long id : order) book.remove(id);          // remove(Object)：先找，再把后面整体往前挪
        sink += book.size();
    }

    static void cancelLinkedList(List<Long> ids, List<Long> order) {
        LinkedList<Long> book = new LinkedList<>(ids);
        for (Long id : order) book.remove(id);          // remove(Object)：从头一个个找，找到了才 O(1) 摘掉
        sink += book.size();
    }

    static void cancelOrderQueue(List<Long> ids, List<Long> order) {
        OrderQueue book = new OrderQueue();
        for (Long id : ids) book.add(id, 1);
        for (Long id : order) book.cancel(id);          // 查索引拿到节点，改两根指针
        sink += book.size();
    }

    static void sumByIndex(LinkedList<Long> list) {
        long s = 0;
        for (int i = 0; i < list.size(); i++) s += list.get(i);   // 每次 get(i) 都从近的一端走过去
        sink += s;
    }

    static void sumByIterator(LinkedList<Long> list) {
        long s = 0;
        for (Iterator<Long> it = list.iterator(); it.hasNext(); ) s += it.next();
        sink += s;
    }

    static double best(Runnable r) {
        for (int i = 0; i < 2; i++) r.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            r.run();
            best = Math.min(best, (System.nanoTime() - t) / 1e6);
        }
        return best;
    }

    static String ratio(double[] prev, int i, double now) {
        return prev == null ? "" : String.format("×%.1f", now / prev[i]);
    }
}
