package labs.algo.sorting;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 手写两种排序，和页面 ④ 对得上：
 * 快排（Lomuto 划分，随机选基准，小段插入排序）—— 先分后治，原地，不稳定；
 * 归并（自顶向下）—— 先治后合，要 O(n) 辅助空间，稳定。
 */
public class Sorts {

    /** Lomuto 划分：以 a[hi] 为基准，比它小的都换到左边，返回基准最终位置。 */
    static int partition(long[] a, int lo, int hi, boolean trace) {
        long pivot = a[hi];
        int i = lo - 1;                          // [lo, i] 都 < pivot
        for (int j = lo; j < hi; j++) {
            if (a[j] < pivot) {
                i++;
                long t = a[i]; a[i] = a[j]; a[j] = t;
                if (trace) System.out.printf("  j=%d a[j]<%d → 换到 i=%d  %s%n", j, pivot, i, Arrays.toString(a));
            } else if (trace) {
                System.out.printf("  j=%d a[j]=%d ≥ %d，不动    %s%n", j, a[j], pivot, Arrays.toString(a));
            }
        }
        long t = a[i + 1]; a[i + 1] = a[hi]; a[hi] = t;   // 基准放到中间
        if (trace) System.out.printf("  基准 %d 放到 %d    %s%n", pivot, i + 1, Arrays.toString(a));
        return i + 1;
    }

    /** 教学版快排。randomPivot=false 时总拿最后一个当基准：有序输入会退化成 O(n²)。 */
    public static void quickSort(long[] a, int lo, int hi, boolean randomPivot) {
        if (hi - lo < 16 && randomPivot) { insertion(a, lo, hi); return; }
        if (lo >= hi) return;
        if (randomPivot) {
            int r = ThreadLocalRandom.current().nextInt(lo, hi + 1);
            long t = a[r]; a[r] = a[hi]; a[hi] = t;
        }
        int p = partition(a, lo, hi, false);
        quickSort(a, lo, p - 1, randomPivot);
        quickSort(a, p + 1, hi, randomPivot);
    }

    static void insertion(long[] a, int lo, int hi) {
        for (int i = lo + 1; i <= hi; i++) {
            long x = a[i]; int j = i - 1;
            while (j >= lo && a[j] > x) { a[j + 1] = a[j]; j--; }
            a[j + 1] = x;
        }
    }

    /** 归并排序（对象版，稳定）：相等时先取左边那个。 */
    @SuppressWarnings("unchecked")
    public static <T> void mergeSort(T[] a, Comparator<? super T> c) {
        T[] buf = (T[]) new Object[a.length];
        mergeSort(a, buf, 0, a.length, c);
    }

    static <T> void mergeSort(T[] a, T[] buf, int lo, int hi, Comparator<? super T> c) {
        if (hi - lo < 2) return;
        int mid = (lo + hi) >>> 1;
        mergeSort(a, buf, lo, mid, c);
        mergeSort(a, buf, mid, hi, c);
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) buf[k++] = c.compare(a[j], a[i]) < 0 ? a[j++] : a[i++];   // 严格小于才取右边：稳定
        while (i < mid) buf[k++] = a[i++];
        while (j < hi) buf[k++] = a[j++];
        System.arraycopy(buf, lo, a, lo, hi - lo);
    }

    /** 不稳定的对象快排，演示同号记录被打乱。 */
    public static <T> void quickSortObj(T[] a, int lo, int hi, Comparator<? super T> c) {
        if (lo >= hi) return;
        T pivot = a[hi]; int i = lo - 1;
        for (int j = lo; j < hi; j++) if (c.compare(a[j], pivot) < 0) { i++; T t = a[i]; a[i] = a[j]; a[j] = t; }
        T t = a[i + 1]; a[i + 1] = a[hi]; a[hi] = t;
        quickSortObj(a, lo, i, c);
        quickSortObj(a, i + 2, hi, c);
    }

    public static void main(String[] args) {
        long[] a = {5, 3, 8, 1, 9, 2, 7, 4};
        System.out.println("划分 " + Arrays.toString(a) + "，基准 = 最后一个 4");
        int p = partition(a, 0, a.length - 1, true);
        System.out.println("基准落在 " + p + "：左边都 < 4，右边都 ≥ 4，4 已经在最终位置上");
        quickSort(a, 0, a.length - 1, true);
        System.out.println("排完 " + Arrays.toString(a));
    }
}
