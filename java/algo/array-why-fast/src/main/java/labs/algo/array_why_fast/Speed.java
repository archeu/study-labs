package labs.algo.array_why_fast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * 同样是 O(1) 或 O(n)，为什么快慢差几倍：连续内存、缓存行、预取。
 * 粗测，不是 JMH：每项先热身，再取 5 次里最快的一次。
 * 运行：java -cp target/classes labs.algo.array_why_fast.Speed
 */
public class Speed {
    static long sink;

    static double best(String name, LongSupplier task) {
        for (int i = 0; i < 3; i++) sink += task.getAsLong();          // 热身，让 JIT 编译
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t = System.nanoTime();
            sink += task.getAsLong();
            best = Math.min(best, System.nanoTime() - t);
        }
        double ms = best / 1e6;
        System.out.printf("  %-34s %9.2f ms%n", name, ms);
        return ms;
    }

    public static void main(String[] args) {
        final int n = 4_000_000;
        final int[] arr = new int[n];
        final List<Integer> al = new ArrayList<>(n);
        final List<Integer> ll = new LinkedList<>();
        for (int i = 0; i < n; i++) { arr[i] = i; al.add(i + 1000); ll.add(i + 1000); }
        final List<Integer> alShuffled = new ArrayList<>(al);
        Collections.shuffle(alShuffled, new Random(42));               // 引用打乱，指向的对象在堆里跳着放

        System.out.println("== 1 把 400 万个数加一遍（都是 O(n)）==");
        best("int[]", () -> { long s = 0; for (int i = 0; i < n; i++) s += arr[i]; return s; });
        best("ArrayList<Integer>", () -> { long s = 0; for (int i = 0; i < n; i++) s += al.get(i); return s; });
        best("ArrayList<Integer>（引用打乱）", () -> { long s = 0; for (int i = 0; i < n; i++) s += alShuffled.get(i); return s; });
        best("LinkedList<Integer>", () -> { long s = 0; for (int x : ll) s += x; return s; });

        System.out.println("\n== 2 按下标访问：顺序 vs 随机（都是 O(1) 下标）==");
        final int[] seq = new int[n], rnd = new int[n];
        for (int i = 0; i < n; i++) seq[i] = i;
        System.arraycopy(seq, 0, rnd, 0, n);
        Random r = new Random(7);
        for (int i = n - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = rnd[i]; rnd[i] = rnd[j]; rnd[j] = t; }
        final int[] big = new int[32 * 1024 * 1024];                 // 128 MB，远大于所有缓存
        final int mask = big.length - 1;
        best("顺序：big[seq[i] * 8]", () -> { long s = 0; for (int i = 0; i < n; i++) s += big[(seq[i] * 8) & mask]; return s; });
        best("随机：big[rnd[i] * 8]", () -> { long s = 0; for (int i = 0; i < n; i++) s += big[(rnd[i] * 8) & mask]; return s; });

        System.out.println("\n== 3 二维数组 4096×4096：按行 vs 按列 ==");
        final int m = 4096;
        final int[][] g = new int[m][m];
        best("按行：g[i][j]", () -> { long s = 0; for (int i = 0; i < m; i++) for (int j = 0; j < m; j++) s += g[i][j]; return s; });
        best("按列：g[j][i]", () -> { long s = 0; for (int i = 0; i < m; i++) for (int j = 0; j < m; j++) s += g[j][i]; return s; });

        System.out.println("\n== 4 步长实验：每隔 k 个 int 改一个（128 MB 数组）==");
        for (int k = 1; k <= 64; k *= 2) {
            final int step = k;
            best("k = " + k + "（改了 " + (big.length / k) + " 个）", () -> { for (int i = 0; i < big.length; i += step) big[i] *= 3; return big[0]; });
        }

        System.out.println("\n== 5 取第 200 万个元素 ==");
        best("ArrayList.get(n/2) × 1000", () -> { long s = 0; for (int i = 0; i < 1000; i++) s += al.get(n / 2); return s; });
        best("LinkedList.get(n/2) × 10", () -> { long s = 0; for (int i = 0; i < 10; i++) s += ll.get(n / 2); return s; });
        System.out.println("\n(sink=" + (sink & 1) + ")");
    }
}
