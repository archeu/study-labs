package labs.algo.two_pointers;

import java.util.Arrays;

/**
 * 双指针的三种样子，都在成交流水上：
 * 1 同向可变窗口：最短一段连续成交，成交量合计 >= K（异常放量）
 * 2 同向时间窗口：过去 60 秒成交量，加新的、减过期的
 * 3 快慢指针：排好序的流水原地去重
 * 4 对撞指针：有序卖单里两笔凑满一张买单
 */
public class Windows {

    /** 最短的一段连续成交，数量合计 >= k；返回 {长度, 起点}，没有返回 {0, -1}。要求 qty 都 >= 0。 */
    public static int[] shortestAtLeast(long[] qty, long k, boolean trace) {
        int best = Integer.MAX_VALUE, bestL = -1, l = 0;
        long sum = 0;
        for (int r = 0; r < qty.length; r++) {
            sum += qty[r];                                   // 右指针进一个
            if (trace) System.out.printf("r=%d 加 %d → [%d,%d] 合计 %d%n", r, qty[r], l, r, sum);
            while (sum >= k) {                               // 合法了：试着从左边缩
                if (r - l + 1 < best) { best = r - l + 1; bestL = l; }
                if (trace) System.out.printf("     合计 %d >= %d，记下长度 %d；左指针移出 %d%n", sum, k, r - l + 1, qty[l]);
                sum -= qty[l++];
            }
        }
        return best == Integer.MAX_VALUE ? new int[]{0, -1} : new int[]{best, bestL};
    }

    /** 暴力：每个起点往后加，O(n²)，用来对拍。 */
    public static int[] shortestAtLeastBrute(long[] qty, long k) {
        int best = Integer.MAX_VALUE, bestL = -1;
        for (int l = 0; l < qty.length; l++) {
            long sum = 0;
            for (int r = l; r < qty.length && r - l + 1 < best; r++) {
                sum += qty[r];
                if (sum >= k) { best = r - l + 1; bestL = l; break; }
            }
        }
        return best == Integer.MAX_VALUE ? new int[]{0, -1} : new int[]{best, bestL};
    }

    /** 过去 windowMs 毫秒的成交量：时间和数量各一个数组，左指针追着过期的走。返回每笔成交时刻的窗口合计。 */
    public static long[] rollingVolume(long[] timeMs, long[] qty, long windowMs) {
        long[] out = new long[qty.length];
        long sum = 0; int l = 0;
        for (int r = 0; r < qty.length; r++) {
            sum += qty[r];
            while (timeMs[l] <= timeMs[r] - windowMs) sum -= qty[l++];   // (now - window, now]
            out[r] = sum;
        }
        return out;
    }

    /** 快慢指针：有序数组原地去重，返回新长度。 */
    public static int dedupSorted(long[] a) {
        if (a.length == 0) return 0;
        int slow = 0;                                   // [0, slow] 是去重后的结果
        for (int fast = 1; fast < a.length; fast++) if (a[fast] != a[slow]) a[++slow] = a[fast];
        return slow + 1;
    }

    /** 对撞指针：升序的卖单数量里找两笔，加起来正好 = need。返回下标对，没有返回 null。 */
    public static int[] twoSumSorted(long[] asc, long need) {
        int i = 0, j = asc.length - 1;
        while (i < j) {
            long s = asc[i] + asc[j];
            if (s == need) return new int[]{i, j};
            if (s < need) i++; else j--;                // 小了，换大一点的左边；大了，换小一点的右边
        }
        return null;
    }

    public static void main(String[] args) {
        long[] qty = {3, 1, 4, 1, 5, 9, 2, 6, 5, 3};
        System.out.println("成交量 " + Arrays.toString(qty) + "，找最短一段合计 >= 15");
        int[] r = shortestAtLeast(qty, 15, true);
        System.out.printf("答案：长度 %d，从下标 %d 开始；暴力对拍 %s%n%n", r[0], r[1], Arrays.toString(shortestAtLeastBrute(qty, 15)));

        long[] t = {0, 20_000, 50_000, 61_000, 70_000, 125_000};
        long[] q = {10, 5, 8, 2, 7, 1};
        System.out.println("过去 60 秒成交量：" + Arrays.toString(rollingVolume(t, q, 60_000)));

        long[] ids = {101, 101, 102, 103, 103, 103, 105};
        int n = dedupSorted(ids);
        System.out.println("去重：" + Arrays.toString(Arrays.copyOf(ids, n)));

        long[] asks = {1, 2, 3, 5, 8, 13};
        System.out.println("两笔卖单凑 11 手：下标 " + Arrays.toString(twoSumSorted(asks, 11)));
    }
}
