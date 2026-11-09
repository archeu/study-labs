package labs.javase.algorithms_everyday;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Supplier;

/**
 * 交易靶场里的几道“算法题”：热销 Top 10、按时间查订单、下单限流、对账排序、TreeMap 里的红黑树。
 * JDK 17：java --add-opens java.base/java.util=ALL-UNNAMED -cp target/classes labs.javase.algorithms_everyday.Demo
 */
public class Demo {
    record Order(long id, long userId, int amountCents, long ts) {}

    public static void main(String[] args) throws Exception {
        Random r = new Random(9527);
        int n = 1_000_000;
        int[] amount = new int[n];
        for (int i = 0; i < n; i++) amount[i] = r.nextInt(100_000_00);   // 0 ~ 10 万元，单位：分

        System.out.println("== 1. 100 万单里找金额最大的 10 单 ==");
        System.out.printf("  全部排序再取前 10         %7.1f ms%n", best(() -> { int[] c = amount.clone(); Arrays.sort(c); return (long) c[n - 1]; }));
        System.out.printf("  大小为 10 的小顶堆          %7.1f ms%n", best(() -> {
            PriorityQueue<Integer> heap = new PriorityQueue<>(11);
            for (int a : amount) { if (heap.size() < 10) heap.offer(a); else if (a > heap.peek()) { heap.poll(); heap.offer(a); } }
            return (long) heap.peek(); }));
        System.out.printf("  Stream sorted().limit(10)   %7.1f ms%n", best(() -> Arrays.stream(amount).boxed().sorted(Comparator.reverseOrder()).limit(10).mapToLong(Integer::longValue).sum()));

        System.out.println("\n== 2. 按时间查订单：100 万个有序时间戳里找 [t1, t2) ==");
        long[] ts = new long[n];
        long t0 = 1_794_000_000_000L;
        for (int i = 0; i < n; i++) ts[i] = t0 + i * 37L + r.nextInt(30);   // 递增
        long from = ts[612_345] + 1, to = from + 60_000;                       // 一分钟窗口
        int[] probes = {0};
        int lo = lowerBound(ts, from, probes), hi = lowerBound(ts, to, probes);
        int scanned = 0, linCount = 0;
        for (long t : ts) { scanned++; if (t >= from && t < to) linCount++; }
        System.out.println("  二分：比较 " + probes[0] + " 次，命中 " + (hi - lo) + " 单；从头扫：比较 " + scanned + " 次，命中 " + linCount + " 单");

        System.out.println("\n== 3. 下单限流：每个用户 60 秒内最多 5 单（滑动窗口） ==");
        Map<Long, Deque<Long>> recent = new HashMap<>();
        long[] arrive = {0, 5, 10, 20, 30, 40, 55, 61, 66, 125};
        StringBuilder sb = new StringBuilder();
        for (long s : arrive) sb.append(s).append("s:").append(allow(recent, 1024L, s * 1000, 5, 60_000) ? "放行" : "拒绝").append("  ");
        System.out.println("  " + sb.toString().trim());

        System.out.println("\n== 4. 对账排序：稳定性，以及比较器写错 ==");
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 8; i++) orders.add(new Order(9527 + i, 1000 + i % 3, (i % 3) * 100_00, t0 + i));
        orders.sort(Comparator.comparingLong(Order::ts));
        orders.sort(Comparator.comparingInt(Order::amountCents));
        System.out.println("  先按时间、再按金额排（稳定排序，同金额保持时间顺序）：" + orders.stream().map(o -> o.amountCents() / 100 + "元#" + o.id()).toList());
        int attempt = 0; String err = null;
        for (; attempt < 500 && err == null; attempt++) {
            List<Integer> xs = new ArrayList<>();
            Random rr = new Random(attempt);
            for (int i = 0; i < 2000; i++) xs.add(rr.nextInt(50));
            try { xs.sort((a, b) -> a > b ? 1 : -1); }               // 相等时也返回 -1：违反约定
            catch (IllegalArgumentException e) { err = e.getMessage(); }
        }
        System.out.println("  比较器相等时返回 -1：" + (err == null ? "500 次都没报错（不代表没问题）" : "第 " + attempt + " 次随机数据报 " + err));

        System.out.println("\n== 5. TreeMap 里就是一棵红黑树：依次放 10 20 30 15 25 5 1 ==");
        TreeMap<Integer, String> tm = new TreeMap<>();
        for (int k : new int[]{10, 20, 30, 15, 25, 5, 1}) {
            tm.put(k, "");
            System.out.println("  放 " + String.format("%2d", k) + " 后：" + shape(tm));
        }
    }

    static int lowerBound(long[] a, long key, int[] probes) {            // 第一个 >= key 的下标
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; probes[0]++; if (a[mid] < key) lo = mid + 1; else hi = mid; }
        return lo;
    }

    static boolean allow(Map<Long, Deque<Long>> recent, long user, long nowMs, int limit, long windowMs) {
        Deque<Long> q = recent.computeIfAbsent(user, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst() <= nowMs - windowMs) q.pollFirst();   // 窗口外的扔掉
        if (q.size() >= limit) return false;
        q.addLast(nowMs);
        return true;
    }

    /** 反射读 TreeMap.Entry：(key 颜色 左子树 右子树)，R 红 B 黑。 */
    static String shape(TreeMap<?, ?> tm) throws Exception {
        Field root = TreeMap.class.getDeclaredField("root"); root.setAccessible(true);
        return node(root.get(tm));
    }
    static String node(Object e) throws Exception {
        if (e == null) return "·";
        Class<?> c = e.getClass();
        Field k = c.getDeclaredField("key"), l = c.getDeclaredField("left"), rt = c.getDeclaredField("right"), col = c.getDeclaredField("color");
        for (Field f : new Field[]{k, l, rt, col}) f.setAccessible(true);
        String me = k.get(e) + (col.getBoolean(e) ? "B" : "R");
        Object L = l.get(e), R = rt.get(e);
        return L == null && R == null ? me : "(" + me + " " + node(L) + " " + node(R) + ")";
    }

    static double best(Supplier<Long> task) {
        double min = Double.MAX_VALUE; long sink = 0;
        for (int i = 0; i < 4; i++) { long s = System.nanoTime(); sink += task.get(); min = Math.min(min, (System.nanoTime() - s) / 1e6); }
        if (sink == 42) System.out.println();
        return min;
    }
}
