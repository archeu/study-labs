package labs.algo.binary_search;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 页面 ⑥ 的坑，每个都跑出来看。 */
public class Pitfalls {

    public static void main(String[] args) {
        overflow();
        deadLoop();
        duplicates();
        negativeReturn();
        wrongOrder();
    }

    /** 坑 1：(lo + hi) / 2 溢出。不用真开 20 亿长的数组：在 [0, Integer.MAX_VALUE) 上找第一个 >= target 的整数。 */
    static void overflow() {
        System.out.println("== 坑 1：(lo + hi) / 2 溢出");
        int target = 2_000_000_000;
        int lo = 0, hi = Integer.MAX_VALUE, steps = 0;
        try {
            while (lo < hi) {
                int mid = (lo + hi) / 2;
                steps++;
                if (mid < 0) throw new ArrayIndexOutOfBoundsException("Index " + mid + " out of bounds");
                if (mid >= target) hi = mid; else lo = mid + 1;
            }
            System.out.println("(lo + hi) / 2 找到 " + lo);
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.printf("(lo + hi) / 2：第 %d 步 lo=%d hi=%d，mid 算成 %s%n", steps, lo, hi, e.getMessage());
        }
        lo = 0; hi = Integer.MAX_VALUE; steps = 0;
        while (lo < hi) { int mid = (lo + hi) >>> 1; steps++; if (mid >= target) hi = mid; else lo = mid + 1; }
        System.out.printf("(lo + hi) >>> 1：%d 步找到 %d%n%n", steps, lo);
    }

    /** 坑 2：hi = mid 配 lo = mid（少了 +1），区间缩不下去。 */
    static void deadLoop() {
        System.out.println("== 坑 2：lo = mid 少了 + 1");
        long[] a = {10, 20, 30, 40};
        int lo = 0, hi = a.length, steps = 0;
        while (lo < hi && steps < 10) {
            int mid = (lo + hi) >>> 1;
            steps++;
            if (a[mid] >= 35) hi = mid; else lo = mid;          // 错：应为 lo = mid + 1
            System.out.printf("  第 %d 次 lo=%d hi=%d%n", steps, lo, hi);
        }
        System.out.println(steps >= 10 ? "  10 次了还没停：lo=2, hi=3 时 mid 永远是 2，死循环" : "停了");
        System.out.println();
    }

    /** 坑 3：Arrays.binarySearch 遇到重复元素，返回的不一定是第一个。 */
    static void duplicates() {
        System.out.println("== 坑 3：重复元素");
        long[] a = {100, 200, 200, 200, 200, 200, 200, 300};
        System.out.printf("Arrays.binarySearch(a, 200) = %d，lowerBound = %d%n%n", Arrays.binarySearch(a, 200L), TradeLog.lowerBound(a, 200));
    }

    /** 坑 4：没找到时返回 -(插入点) - 1，直接当下标用就越界。 */
    static void negativeReturn() {
        System.out.println("== 坑 4：没找到时的返回值");
        long[] a = {100, 200, 300};
        int r = Arrays.binarySearch(a, 250L);
        System.out.printf("Arrays.binarySearch(a, 250) = %d，插入点 = -r - 1 = %d%n", r, -r - 1);
        try {
            System.out.println(a[r]);
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("直接 a[r]：" + e.getMessage());
        }
        System.out.println();
    }

    /** 坑 5：数据是降序的（买盘价格从高到低），却用升序的二分。 */
    static void wrongOrder() {
        System.out.println("== 坑 5：买盘按价格从高到低存着");
        Long[] bids = {10_120L, 10_110L, 10_100L, 10_090L, 10_080L};
        System.out.printf("Arrays.binarySearch(bids, 10090) = %d（错：明明在下标 3）%n",
                Arrays.binarySearch(bids, 10_090L));
        System.out.printf("Arrays.binarySearch(bids, 10090, reverseOrder()) = %d%n",
                Arrays.binarySearch(bids, 10_090L, Collections.reverseOrder()));
        List<Long> list = Arrays.asList(bids);
        System.out.printf("Collections.binarySearch(list, 10090, reverseOrder()) = %d%n",
                Collections.binarySearch(list, 10_090L, Collections.reverseOrder()));
    }
}
