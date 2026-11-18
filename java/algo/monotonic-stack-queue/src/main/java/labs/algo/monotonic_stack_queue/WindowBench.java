package labs.algo.monotonic_stack_queue;

import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;

/**
 * 过去 60 秒最高价，四种做法每笔花多少时间。
 * 行情：每秒 1 万笔（每 100 微秒一笔），随机游走，价格单位“分”。窗口 60 秒 = 60 万笔。
 * 别在 Maven 进程里跑：java -Xms1g -Xmx1g -cp algo/monotonic-stack-queue/target/classes labs.algo.monotonic_stack_queue.WindowBench
 */
public class WindowBench {
    static final int N = 3_000_000;              // 300 秒的行情
    static final long WINDOW_US = 60_000_000L;   // 60 秒，时间单位微秒
    static long[] time = new long[N], price = new long[N];

    public static void main(String[] args) {
        Random r = new Random(42);
        long p = 6_000_000;                       // 60000.00 元
        for (int i = 0; i < N; i++) { time[i] = i * 100L; p += r.nextInt(201) - 100; price[i] = p; }

        for (int round = 1; round <= 3; round++) {   // 第 1 轮是预热
            System.out.println("-- 第 " + round + " 轮");
            report("单调队列（long 数组环）", N, deque());
            report("TreeMap<价格, 笔数>", N, treeMap());
            report("PriorityQueue + 延迟删除", N, heap());
            report("每笔重扫整个窗口（只测 2000 笔）", 2000, naive(2000));
        }
    }

    static void report(String name, int n, long[] res) {
        System.out.printf("%-26s 每笔 %8.1f ns   校验和 %d   队列/堆最长 %d%n", name, res[0] / (double) n, res[1], res[2]);
    }

    /** 单调队列：用两个 long 数组当环，避免 long[] 装箱。 */
    static long[] deque() {
        int cap = 1 << 20; long[] qt = new long[cap], qp = new long[cap]; int h = 0, t = 0, mask = cap - 1, peak = 0;
        long sum = 0, t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            long now = time[i], pr = price[i];
            while (t != h && qp[(t - 1) & mask] <= pr) t--;
            qt[t & mask] = now; qp[t & mask] = pr; t++;
            while (qt[h & mask] <= now - WINDOW_US) h++;
            peak = Math.max(peak, t - h);
            sum += qp[h & mask];
        }
        return new long[]{System.nanoTime() - t0, sum, peak};
    }

    static long[] treeMap() {
        TreeMap<Long, Integer> m = new TreeMap<>();
        long sum = 0, t0 = System.nanoTime(); int j = 0;
        for (int i = 0; i < N; i++) {
            m.merge(price[i], 1, Integer::sum);
            while (time[j] <= time[i] - WINDOW_US) {   // 出窗口的那笔，计数减一
                Integer c = m.get(price[j]);
                if (c == 1) m.remove(price[j]); else m.put(price[j], c - 1);
                j++;
            }
            sum += m.lastKey();
        }
        return new long[]{System.nanoTime() - t0, sum, m.size()};
    }

    /** 堆里放 {价格, 时间}，按价格大顶。过期的不马上删，等它浮到堆顶才扔。 */
    static long[] heap() {
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Long.compare(b[0], a[0]));
        long sum = 0, t0 = System.nanoTime(); int peak = 0;
        for (int i = 0; i < N; i++) {
            pq.offer(new long[]{price[i], time[i]});
            while (pq.peek()[1] <= time[i] - WINDOW_US) pq.poll();
            peak = Math.max(peak, pq.size());
            sum += pq.peek()[0];
        }
        return new long[]{System.nanoTime() - t0, sum, peak};
    }

    /** 每笔把窗口里 60 万笔从头扫一遍。只测窗口已满之后的 n 笔。 */
    static long[] naive(int n) {
        long sum = 0, t0 = System.nanoTime();
        int start = 600_000;
        for (int i = start; i < start + n; i++) {
            long mx = Long.MIN_VALUE;
            for (int k = i - 599_999; k <= i; k++) if (price[k] > mx) mx = price[k];
            sum += mx;
        }
        return new long[]{System.nanoTime() - t0, sum, 600_000};
    }
}
