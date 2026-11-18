package labs.algo.big_o_counting;

/**
 * 不计时，只数次数：每段代码里放一个计数器，数“最内层的基本操作”执行了多少次。
 * 次数和机器、JDK、JIT 都无关，所以每台机器跑出来都一样。
 */
public class Count {

    // ---------- 1 双重循环：找有没有两个数加起来等于 target ----------
    static long pairSumCompares(int[] a, int target) {
        long ops = 0;
        for (int i = 0; i < a.length; i++) {
            for (int j = i + 1; j < a.length; j++) {
                ops++;                                  // 基本操作：一次相加比较
                if (a[i] + a[j] == target) return ops;
            }
        }
        return ops;
    }

    // ---------- 2 线性查找：最好、最坏、平均 ----------
    static long linearSearchCompares(int[] a, int x) {
        long ops = 0;
        for (int v : a) {
            ops++;
            if (v == x) return ops;
        }
        return ops;
    }

    // ---------- 3 二分查找：每次砍一半 ----------
    static long binarySearchSteps(int[] a, int x) {
        long ops = 0;
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            ops++;                                      // 基本操作：看一次中间元素
            int mid = (lo + hi) >>> 1;
            if (a[mid] < x) lo = mid + 1;
            else if (a[mid] > x) hi = mid - 1;
            else return ops;
        }
        return ops;
    }

    // ---------- 4 动态数组：一路 add n 次，数扩容时一共搬了多少个元素 ----------
    interface Growth { int next(int cap); }

    static long[] grow(int n, Growth g) {
        long copies = 0, grows = 0, maxOne = 0;
        int cap = 10, size = 0;                         // 和 ArrayList 一样从 10 起步
        for (int k = 0; k < n; k++) {
            if (size == cap) {
                copies += size;                         // 扩容 = 新开数组 + 把旧的 size 个搬过去
                maxOne = Math.max(maxOne, size);
                grows++;
                cap = Math.max(g.next(cap), cap + 1);
            }
            size++;                                     // 放进新元素本身：1 次
        }
        return new long[]{copies, grows, maxOne, cap};
    }

    static int[] seq(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i * 2 + 1;   // 全是正奇数，有序
        return a;
    }

    public static void main(String[] args) {
        System.out.println("== 1 双重循环找两数之和（找不到，跑满）==");
        long prev = 0;
        for (int n = 1000; n <= 16000; n *= 2) {
            long ops = pairSumCompares(seq(n), -1);
            System.out.printf("  n = %6d   比较 %,13d 次   n(n-1)/2 = %,13d   比上一行 %s%n",
                    n, ops, (long) n * (n - 1) / 2, prev == 0 ? "-" : String.format("×%.2f", (double) ops / prev));
            prev = ops;
        }

        System.out.println();
        System.out.println("== 2 线性查找 n = 1000：同一段代码，三种答案 ==");
        int n = 1000;
        int[] a = seq(n);
        long best = linearSearchCompares(a, a[0]);
        long worst = linearSearchCompares(a, -1);
        long sum = 0;
        for (int v : a) sum += linearSearchCompares(a, v);
        System.out.printf("  最好（在第一个）   %d 次%n", best);
        System.out.printf("  最坏（不存在）     %d 次%n", worst);
        System.out.printf("  平均（每个位置各找一次） %.1f 次   (n+1)/2 = %.1f%n", (double) sum / n, (n + 1) / 2.0);

        System.out.println();
        System.out.println("== 3 二分查找：找不到时最多看几次 ==");
        for (int p = 10; p <= 22; p += 2) {
            int m = 1 << p;
            long steps = binarySearchSteps(seq(m), Integer.MAX_VALUE);
            System.out.printf("  n = 2^%d = %,10d   看了 %2d 次   log2(n)+1 = %d%n", p, m, steps, p + 1);
        }
        int m4 = 4_000_000;
        System.out.printf("  n = %,d（一天的订单）   看了 %d 次%n", m4, binarySearchSteps(seq(m4), Integer.MAX_VALUE));

        System.out.println();
        System.out.println("== 4 add 1,000,000 次：不同扩容策略一共搬了多少个元素 ==");
        int adds = 1_000_000;
        Object[][] rules = {
                {"×2", (Growth) c -> c * 2},
                {"×1.5（ArrayList）", (Growth) c -> c + (c >> 1)},
                {"×1.1", (Growth) c -> c + c / 10},
                {"+10", (Growth) c -> c + 10},
                {"+1000", (Growth) c -> c + 1000},
        };
        for (Object[] r : rules) {
            long[] s = grow(adds, (Growth) r[1]);
            System.out.printf("  %-16s 搬 %,15d 个   平均每次 add 搬 %8.2f 个   扩容 %,6d 次   最大一次搬 %,9d 个%n",
                    r[0], s[0], (double) s[0] / adds, s[1], s[2]);
        }
    }
}
