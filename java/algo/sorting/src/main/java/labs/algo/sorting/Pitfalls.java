package labs.algo.sorting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 页面 ⑥ 的坑。 */
public class Pitfalls {
    static long cmp;

    public static void main(String[] args) {
        sortedInput();
        stringIds();
        brokenComparator();
    }

    /** 坑 1：固定拿最后一个当基准，输入本来就有序。 */
    static void sortedInput() {
        System.out.println("== 坑 1：有序输入 + 固定基准");
        for (int n : new int[]{2_000, 20_000, 100_000}) {
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = i;
            long t0 = System.nanoTime();
            try {
                Sorts.quickSort(a, 0, n - 1, false);
                System.out.printf("n=%,d：%d ms（比较约 n²/2 = %,d 次）%n", n, (System.nanoTime() - t0) / 1_000_000, (long) n * n / 2);
            } catch (StackOverflowError e) {
                System.out.printf("n=%,d：StackOverflowError（递归深度 = n）%n", n);
            }
        }
        long[] a = new long[100_000];
        for (int i = 0; i < a.length; i++) a[i] = i;
        long t0 = System.nanoTime();
        Sorts.quickSort(a, 0, a.length - 1, true);
        System.out.printf("n=100,000 随机基准：%d ms%n%n", (System.nanoTime() - t0) / 1_000_000);
    }

    /** 坑 6：成交号存成 String，按字典序排。 */
    static void stringIds() {
        System.out.println("== 坑 6：成交号是 String");
        List<String> ids = new ArrayList<>(Arrays.asList("9", "10", "100", "11", "2"));
        Collections.sort(ids);
        System.out.println("字典序：" + ids + "   两份流水的 id 位数不统一时，对账的双指针会错位");
        System.out.println();
    }

    /** 坑 3：比较器不满足约定（这里：随机返回），TimSort 可能直接抛异常。 */
    static void brokenComparator() {
        System.out.println("== 坑 3：比较器不讲道理");
        Random r = new Random(5);
        Integer[] a = new Integer[2000];
        for (int i = 0; i < a.length; i++) a[i] = r.nextInt(100);
        int thrown = 0;
        for (int k = 0; k < 20; k++) {
            Integer[] b = a.clone();
            try { Arrays.sort(b, (x, y) -> r.nextInt(3) - 1); }
            catch (IllegalArgumentException e) { thrown++; if (thrown == 1) System.out.println("抛出：" + e.getMessage()); }
        }
        System.out.println("20 次里抛了 " + thrown + " 次；没抛的那些次，结果是乱的");
    }
}
