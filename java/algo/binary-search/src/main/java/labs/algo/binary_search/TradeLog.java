package labs.algo.binary_search;

/**
 * 成交流水按时间追加，天然有序。查 [from, to] 时间段的成交：两次二分找边界。
 * 统一写法：左闭右开 [lo, hi)，找“第一个让 ok(i) 为真的下标”。
 */
public class TradeLog {

    /** 第一个 >= t 的下标；都比 t 小就返回 n。 */
    public static int lowerBound(long[] a, long t) {
        int lo = 0, hi = a.length;               // 答案在 [lo, hi] 里；lo 左边都 < t，hi 及右边都 >= t
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;           // 无符号右移：lo + hi 溢出成负数也能得到对的中点
            if (a[mid] >= t) hi = mid;           // mid 是“真”，答案在 mid 或更左
            else lo = mid + 1;                   // mid 是“假”，答案在 mid 右边
        }
        return lo;                               // lo == hi，就是第一个“真”
    }

    /** 第一个 > t 的下标。和 lowerBound 只差一个等号。 */
    public static int upperBound(long[] a, long t) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] > t) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    static long hms(int h, int m, int s) { return h * 3600L + m * 60 + s; }

    static String fmt(long t) { return String.format("%02d:%02d:%02d", t / 3600, t / 60 % 60, t % 60); }

    /** 带打印的 lowerBound / upperBound，和页面 ④ 的动画一步一步对得上。 */
    static int trace(long[] a, long t, boolean strict) {
        int lo = 0, hi = a.length, step = 0;
        System.out.printf("%s(%s)：找第一个 %s %s 的下标%n", strict ? "upperBound" : "lowerBound", fmt(t), strict ? ">" : ">=", fmt(t));
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            boolean ok = strict ? a[mid] > t : a[mid] >= t;
            System.out.printf("  第 %d 次  lo=%2d hi=%2d mid=%2d  a[mid]=%s  %s → %s%n", ++step, lo, hi, mid, fmt(a[mid]),
                    ok ? "真" : "假", ok ? "hi = mid = " + mid : "lo = mid + 1 = " + (mid + 1));
            if (ok) hi = mid; else lo = mid + 1;
        }
        System.out.printf("  lo == hi == %d，比较了 %d 次%n", lo, step);
        return lo;
    }

    public static void main(String[] args) {
        long[] a = {
            hms(19, 58, 10), hms(19, 59, 2), hms(19, 59, 40), hms(19, 59, 59),
            hms(20, 0, 0), hms(20, 0, 0), hms(20, 0, 0), hms(20, 1, 30),
            hms(20, 2, 15), hms(20, 3, 0), hms(20, 4, 59), hms(20, 5, 0),
            hms(20, 5, 0), hms(20, 5, 1), hms(20, 6, 40), hms(20, 7, 5)};
        int from = trace(a, hms(20, 0, 0), false);
        int to = trace(a, hms(20, 5, 0), true);
        System.out.printf("%n[20:00:00, 20:05:00] 闭区间：下标 [%d, %d)，共 %d 笔%n", from, to, to - from);
        int to2 = lowerBound(a, hms(20, 5, 0));
        System.out.printf("[20:00:00, 20:05:00) 左闭右开：下标 [%d, %d)，共 %d 笔（20:05:00 那两笔不算）%n", from, to2, to2 - from);
        System.out.printf("找 21:00:00：lowerBound = %d（= n，说明都比它早）%n", lowerBound(a, hms(21, 0, 0)));
    }
}
