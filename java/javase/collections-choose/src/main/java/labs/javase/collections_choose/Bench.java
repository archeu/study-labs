package labs.javase.collections_choose;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * 粗测：同一件事，换不同集合各做一遍。不是 JMH，每项先热身 5 轮、再测 7 轮取中位数，只看量级和倍数。
 * JDK 17：java -Xms1g -Xmx1g -cp target/classes labs.javase.collections_choose.Bench
 */
public class Bench {
    static long sink;   // 防止 JIT 把结果当死代码删掉

    static double ms(LongSupplier task) {
        for (int i = 0; i < 5; i++) sink += task.getAsLong();
        double[] t = new double[7];
        for (int i = 0; i < t.length; i++) {
            long s = System.nanoTime();
            sink += task.getAsLong();
            t[i] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(t);
        return t[t.length / 2];
    }

    static void row(String scene, String impl, double v) {
        System.out.printf("%-26s %-34s %10.3f ms%n", scene, impl, v);
    }

    public static void main(String[] args) {
        System.out.println(System.getProperty("java.version") + " · " + Runtime.getRuntime().availableProcessors() + " 核");

        int n = 1_000_000;
        row("尾部追加 100 万", "ArrayList（默认容量）", ms(() -> { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(i); return l.size(); }));
        row("尾部追加 100 万", "ArrayList（预设容量）", ms(() -> { List<Integer> l = new ArrayList<>(n); for (int i = 0; i < n; i++) l.add(i); return l.size(); }));
        row("尾部追加 100 万", "LinkedList", ms(() -> { List<Integer> l = new LinkedList<>(); for (int i = 0; i < n; i++) l.add(i); return l.size(); }));
        row("尾部追加 100 万", "ArrayDeque", ms(() -> { Deque<Integer> d = new ArrayDeque<>(); for (int i = 0; i < n; i++) d.addLast(i); return d.size(); }));

        List<Integer> al = new ArrayList<>(); List<Integer> ll = new LinkedList<>();
        for (int i = 0; i < n; i++) { al.add(i); ll.add(i); }
        row("for-each 遍历 100 万", "ArrayList", ms(() -> { long s = 0; for (int x : al) s += x; return s; }));
        row("for-each 遍历 100 万", "LinkedList", ms(() -> { long s = 0; for (int x : ll) s += x; return s; }));

        int g = 20_000;
        List<Integer> al2 = new ArrayList<>(al.subList(0, g)); List<Integer> ll2 = new LinkedList<>(al2);
        row("按下标 get(i) 2 万次", "ArrayList", ms(() -> { long s = 0; for (int i = 0; i < g; i++) s += al2.get(i); return s; }));
        row("按下标 get(i) 2 万次", "LinkedList", ms(() -> { long s = 0; for (int i = 0; i < g; i++) s += ll2.get(i); return s; }));

        int h = 50_000;
        row("头部插入 5 万", "ArrayList.add(0, x)", ms(() -> { List<Integer> l = new ArrayList<>(); for (int i = 0; i < h; i++) l.add(0, i); return l.size(); }));
        row("头部插入 5 万", "LinkedList.addFirst", ms(() -> { LinkedList<Integer> l = new LinkedList<>(); for (int i = 0; i < h; i++) l.addFirst(i); return l.size(); }));
        row("头部插入 5 万", "ArrayDeque.addFirst", ms(() -> { Deque<Integer> d = new ArrayDeque<>(); for (int i = 0; i < h; i++) d.addFirst(i); return d.size(); }));

        int u = 20_000;
        row("去重 2 万个请求号", "ArrayList.contains 再 add", ms(() -> { List<Long> l = new ArrayList<>(); for (long i = 0; i < u; i++) if (!l.contains(i)) l.add(i); return l.size(); }));
        row("去重 2 万个请求号", "HashSet.add", ms(() -> { Set<Long> s = new HashSet<>(); for (long i = 0; i < u; i++) s.add(i); return s.size(); }));
        row("去重 2 万个请求号", "TreeSet.add", ms(() -> { Set<Long> s = new TreeSet<>(); for (long i = 0; i < u; i++) s.add(i); return s.size(); }));

        // 10 万单分给 1 万个用户，查 1 万次“某用户的订单”
        Random r = new Random(42);
        long[][] orders = new long[100_000][];
        for (int i = 0; i < orders.length; i++) orders[i] = new long[]{9527 + i, r.nextInt(10_000)};
        List<long[]> flat = Arrays.asList(orders);
        Map<Long, List<long[]>> byUser = new HashMap<>();
        for (long[] o : orders) byUser.computeIfAbsent(o[1], k -> new ArrayList<>()).add(o);
        int q = 10_000;
        row("按用户查订单 1 万次", "ArrayList 全表扫", ms(() -> { long c = 0; for (int i = 0; i < q; i++) { long uid = i; for (long[] o : flat) if (o[1] == uid) c++; } return c; }));
        row("按用户查订单 1 万次", "HashMap.get", ms(() -> { long c = 0; for (int i = 0; i < q; i++) { List<long[]> l = byUser.get((long) i); if (l != null) c += l.size(); } return c; }));

        // 2 万个超时任务，按到期时间依次取出
        int k = 20_000;
        long[] deadlines = new long[k];
        for (int i = 0; i < k; i++) deadlines[i] = r.nextInt(30 * 60_000);
        row("按到期先后取出 2 万个", "ArrayList 每次扫最小值", ms(() -> { List<Long> l = new ArrayList<>(); for (long d : deadlines) l.add(d); long s = 0; while (!l.isEmpty()) { int mi = 0; for (int i = 1; i < l.size(); i++) if (l.get(i) < l.get(mi)) mi = i; s += l.remove(mi); } return s; }));
        row("按到期先后取出 2 万个", "PriorityQueue offer/poll", ms(() -> { PriorityQueue<Long> pq = new PriorityQueue<>(); for (long d : deadlines) pq.offer(d); long s = 0; while (!pq.isEmpty()) s += pq.poll(); return s; }));
        row("按到期先后取出 2 万个", "TreeMap 当队列", ms(() -> { TreeMap<Long, Integer> tm = new TreeMap<>(); for (long d : deadlines) tm.merge(d, 1, Integer::sum); long s = 0; while (!tm.isEmpty()) s += tm.pollFirstEntry().getKey(); return s; }));

        System.out.println("sink " + (sink & 1));
    }
}
