package labs.algo.lru_lfu_limit;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * 第 11 站：行情快照缓存，容量 100，交易对 1 万个。三种访问模式，比 FIFO / LRU / LFU 的命中率。
 * A 稳定热点：访问服从 Zipf（少数热门交易对占大部分）
 * B 热点 + 扫描：每 1000 次正常访问夹一次报表扫描，按顺序把 500 个冷门交易对各读一遍（命中率只算正常访问）
 * C 热点漂移：前一半的热门是 0~99 号，后一半换成 5000~5099 号
 */
public class HitRate {

    interface Cache { boolean access(int key); }

    static Cache fifo(final int cap) {
        return new Cache() {
            final ArrayDeque<Integer> q = new ArrayDeque<Integer>(); final Set<Integer> s = new HashSet<Integer>();
            public boolean access(int k) {
                if (s.contains(k)) return true;
                if (s.size() == cap) s.remove(q.pollFirst());
                q.addLast(k); s.add(k); return false;
            }
        };
    }

    static Cache lru(final int cap) {
        return new Cache() {
            final LruCache<Integer, Boolean> c = new LruCache<Integer, Boolean>(cap);
            public boolean access(int k) { if (c.get(k) != null) return true; c.put(k, Boolean.TRUE); return false; }
        };
    }

    static Cache lfu(final int cap) {
        return new Cache() {
            final LfuCache<Integer, Boolean> c = new LfuCache<Integer, Boolean>(cap);
            public boolean access(int k) { if (c.get(k) != null) return true; c.put(k, Boolean.TRUE); return false; }
        };
    }

    /** Zipf(s=1) 抽样：预先算好累积分布，二分查找（第 9 课）。 */
    static final class Zipf {
        final double[] cdf; final Random r;
        Zipf(int n, Random r) {
            this.r = r; cdf = new double[n]; double sum = 0;
            for (int i = 0; i < n; i++) { sum += 1.0 / (i + 1); cdf[i] = sum; }
            for (int i = 0; i < n; i++) cdf[i] /= sum;
        }
        int next() {
            double u = r.nextDouble(); int lo = 0, hi = cdf.length - 1;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (cdf[mid] < u) lo = mid + 1; else hi = mid; }
            return lo;
        }
    }

    static int[] workload(char kind, int len, long seed) {
        Random r = new Random(seed);
        Zipf z = new Zipf(10000, r);
        int[] w = new int[len];
        int i = 0, scanFrom = 9000;
        while (i < len) {
            if (kind == 'B' && i > 0 && i % 1000 == 0) {
                for (int k = 0; k < 500 && i < len; k++) w[i++] = -(scanFrom + k) - 1;  // 报表扫描冷门交易对（负数标记，不计入命中率）
                continue;
            }
            int key = z.next();
            if (kind == 'C' && i >= len / 2) key = (key + 5000) % 10000;            // 热点整体换一批
            w[i++] = key;
        }
        return w;
    }

    /** 只统计正常读取的命中率；扫描请求照样进缓存（会把东西挤出去），但不算进分母。 */
    static double run(Cache c, int[] w) {
        int hit = 0, n = 0;
        for (int k : w) {
            if (k < 0) { c.access(-k - 1); continue; }
            n++;
            if (c.access(k)) hit++;
        }
        return 100.0 * hit / n;
    }

    public static void main(String[] args) {
        int cap = 100, len = 200000;
        String[] names = {"A 稳定热点", "B 热点 + 报表扫描", "C 热点漂移"};
        char[] kinds = {'A', 'B', 'C'};
        System.out.println("容量 " + cap + "，交易对 1 万个，每种模式 " + len + " 次访问：命中率");
        for (int t = 0; t < 3; t++) {
            int[] w = workload(kinds[t], len, 24);
            System.out.printf("  %-14s FIFO %5.1f%%   LRU %5.1f%%   LFU %5.1f%%%n",
                    names[t], run(fifo(cap), w), run(lru(cap), w), run(lfu(cap), w));
        }
    }
}
