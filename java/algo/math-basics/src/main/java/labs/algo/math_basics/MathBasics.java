package labs.algo.math_basics;

/**
 * 第 0 课 ⑤ 手推一遍：撮合引擎里的 6 道数学题。
 * 先在纸上算，再跑这个类对答案。
 */
public class MathBasics {

    /** ⌊log₂ n⌋，n ≥ 1。不经过 double，没有精度问题。 */
    static int floorLog2(long n) {
        return 63 - Long.numberOfLeadingZeros(n);
    }

    /** ⌈a / b⌉，a ≥ 0，b > 0。JDK 18 起可以直接用 Math.ceilDiv。 */
    static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /** C(n, k)：边乘边除，每一步结果都是整数，不先算 n!。 */
    static long comb(int n, int k) {
        k = Math.min(k, n - k);
        long r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;      // 先乘后除：r*(n-k+i) 一定能被 i 整除
        }
        return r;
    }

    static long factorial(int n) {
        long r = 1;
        for (int i = 2; i <= n; i++) r *= i;   // 21! 起悄悄溢出
        return r;
    }

    public static void main(String[] args) {
        System.out.println("== 1 幂：环形队列容量取 2 的幂 ==");
        int cap = 1 << 20;
        int i = 3_000_007;
        System.out.println("1 << 20            = " + cap);
        System.out.println(i + " % cap      = " + (i % cap));
        System.out.println(i + " & (cap-1)  = " + (i & (cap - 1)) + "   （容量是 2 的幂时两者相等）");

        System.out.println("\n== 2 对数：100 万个价位里二分，最多比几次 ==");
        int levels = 1_000_000;
        System.out.println("floor(log2 1e6)    = " + floorLog2(levels));
        System.out.println("最多比较次数        = " + (floorLog2(levels) + 1));
        for (int n : new int[]{1024, 1_000_000, 1_000_000_000}) {
            System.out.println("  n = " + n + "  折半到 1 要 " + floorLog2(n) + " 次");
        }

        System.out.println("\n== 3 取整：1003 条成交，每页 20 条 ==");
        int total = 1003, size = 20;
        long pages = ceilDiv(total, size);
        System.out.println("1003 / 20          = " + (total / size) + "   （整数除法，向零截掉小数）");
        System.out.println("ceilDiv(1003, 20)  = " + pages);
        System.out.println("最后一页条数        = " + (total - (pages - 1) * size));
        int priceCents = 10150;
        System.out.println("10150 分 = " + priceCents / 100 + " 元 " + priceCents % 100 + " 分");

        System.out.println("\n== 4 取模：按订单号 hash 分到 16 个分片 ==");
        String orderId = "c-7781-sell";
        int h = orderId.hashCode();
        System.out.println("hashCode           = " + h);
        System.out.println("h % 16             = " + (h % 16) + "   （负数会落到负下标）");
        System.out.println("Math.floorMod(h,16)= " + Math.floorMod(h, 16));
        System.out.println("h & 15             = " + (h & 15));

        System.out.println("\n== 5 求和：10 万张单两两比一次，一共几次 ==");
        int n = 100_000;
        int wrong = n * (n - 1) / 2;
        long right = (long) n * (n - 1) / 2;
        System.out.println("int  n*(n-1)/2     = " + wrong + "   ✗ 溢出");
        System.out.println("long n*(n-1)/2     = " + right);
        long copies = 0;
        for (long c = 1; c < 1_000_000; c *= 2) copies += c;   // 动态数组每次翻倍要拷贝的元素
        System.out.println("翻倍扩容到 100 万，累计拷贝 1+2+…+2^19 = " + copies + "   （= 2^20 - 1，不到 2n）");

        System.out.println("\n== 6 排列组合：策略参数 ==");
        System.out.println("5 个步骤排顺序 5!   = " + factorial(5));
        System.out.println("20 个交易对选 3 个  = " + comb(20, 3));
        System.out.println("20 个开关全部组合   = " + (1L << 20));
        System.out.println("20!                = " + factorial(20));
        System.out.println("21!（long 溢出）    = " + factorial(21) + "   ✗");
    }
}
