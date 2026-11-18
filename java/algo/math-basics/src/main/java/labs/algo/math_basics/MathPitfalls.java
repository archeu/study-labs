package labs.algo.math_basics;

/** 第 0 课 ⑥：算法里最常见的数学坑，每一行都是真跑的结果。 */
public class MathPitfalls {

    static long powMod(long a, long e, long m) {       // 快速幂：O(log e) 次乘法
        long r = 1;
        a %= m;
        while (e > 0) {
            if ((e & 1) == 1) r = r * a % m;
            a = a * a % m;
            e >>= 1;
        }
        return r;
    }

    static long gcd(long a, long b) {                  // 欧几里得：O(log min(a,b))
        return b == 0 ? a : gcd(b, a % b);
    }

    public static void main(String[] args) {
        System.out.println("== 坑 1 负数的除法和取模 ==");
        System.out.println("-7 / 2             = " + (-7 / 2));
        System.out.println("-7 >> 1            = " + (-7 >> 1));
        System.out.println("Math.floorDiv(-7,2)= " + Math.floorDiv(-7, 2));
        System.out.println("-7 % 3             = " + (-7 % 3));
        System.out.println("Math.floorMod(-7,3)= " + Math.floorMod(-7, 3));

        System.out.println("\n== 坑 2 Math.round 的 .5 ==");
        System.out.println("round(2.5)  = " + Math.round(2.5) + "   round(-2.5) = " + Math.round(-2.5) + "   round(-2.6) = " + Math.round(-2.6));
        System.out.println("rint(2.5)   = " + Math.rint(2.5) + "   rint(3.5)  = " + Math.rint(3.5) + "   （银行家舍入）");

        System.out.println("\n== 坑 3 用 Math.log 算 log 再取整 ==");
        System.out.println("Math.log(1000)/Math.log(10)       = " + Math.log(1000) / Math.log(10));
        System.out.println("(int) 上面那个                      = " + (int) (Math.log(1000) / Math.log(10)) + "   ✗ 应为 3");
        System.out.println("Math.log10(1000)                  = " + Math.log10(1000));
        StringBuilder ten = new StringBuilder();
        long v = 1;
        for (int k = 0; k < 19; k++, v *= 10) {
            int viaDouble = (int) (Math.log(v) / Math.log(10));
            if (viaDouble != k) ten.append("10^").append(k).append("→").append(viaDouble).append(' ');
        }
        System.out.println("10^0..10^18 用 log(v)/log(10) 取整，算错的：" + ten);
        StringBuilder two = new StringBuilder();
        for (int k = 1; k < 63; k++) {
            long w = (1L << k) - 1;                       // 正确答案 k-1
            if ((int) (Math.log(w) / Math.log(2)) != k - 1) two.append(k).append(' ');
        }
        System.out.println("2^k-1 用 log(v)/log(2) 取整，算成 k 的 k：" + two);
        System.out.println("63 - Long.numberOfLeadingZeros(1L<<29) = " + (63 - Long.numberOfLeadingZeros(1L << 29)));

        System.out.println("\n== 坑 4 Math.pow 返回 double ==");
        long exact = 1;
        for (int k = 0; k < 39; k++) exact *= 3;
        System.out.println("3^39 循环乘         = " + exact);
        System.out.println("(long)Math.pow(3,39)= " + (long) Math.pow(3, 39) + (exact == (long) Math.pow(3, 39) ? "" : "   ✗ 差了 " + ((long) Math.pow(3, 39) - exact)));

        System.out.println("\n== 坑 5 移位超过位宽 ==");
        System.out.println("1 << 31  = " + (1 << 31));
        System.out.println("1 << 32  = " + (1 << 32) + "   （int 只看移位数的低 5 位，32 当 0）");
        System.out.println("1L << 32 = " + (1L << 32));

        System.out.println("\n== 坑 6 中间结果溢出 ==");
        int lo = 1_500_000_000, hi = 2_000_000_000;
        System.out.println("(lo+hi)/2          = " + (lo + hi) / 2 + "   ✗");
        System.out.println("lo+(hi-lo)/2       = " + (lo + (hi - lo) / 2));
        System.out.println("(lo+hi)>>>1        = " + ((lo + hi) >>> 1));
        int n = 100_000;
        System.out.println("(long)(n*(n+1))/2  = " + (long) (n * (n + 1)) / 2 + "   ✗ 先乘后转，晚了");
        System.out.println("(long)n*(n+1)/2    = " + (long) n * (n + 1) / 2);
        int M = 1_000_000_007, a = 999_999_999, b = 999_999_999;
        System.out.println("int  a*b%M         = " + a * b % M + "   ✗");
        System.out.println("long (long)a*b%M   = " + (long) a * b % M);
        try {
            Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            System.out.println("Math.multiplyExact(a,b) → ArithmeticException: " + e.getMessage());
        }

        System.out.println("\n== 第三圈 快速幂和最大公约数 ==");
        System.out.println("2^(10^18) mod 1e9+7 = " + powMod(2, 1_000_000_000_000_000_000L, M) + "   （约 60 轮循环）");
        System.out.println("gcd(84, 36)        = " + gcd(84, 36));
        System.out.println("最小公倍数 lcm(84,36) = " + 84 / gcd(84, 36) * 36);
    }
}
