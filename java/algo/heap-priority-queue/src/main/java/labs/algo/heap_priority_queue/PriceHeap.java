package labs.algo.heap_priority_queue;

import java.util.Arrays;

/**
 * 手写一个小顶堆（卖单簿：要价最低的在堆顶），每一步打印数组和挪动过程。
 * 和页面 ④ 的分步动画是同一串操作：入 105 103 108 101 104，再出两次。
 *
 * 数组下标 i 的父亲是 (i-1)/2，两个孩子是 2i+1、2i+2；不存指针。
 */
public class PriceHeap {
    private long[] a = new long[4];
    private int size;

    /** 入堆：放到末尾，再往上浮（sift up）。O(log n) */
    public void offer(long price) {
        if (size == a.length) a = Arrays.copyOf(a, size * 2);
        int k = size++;
        StringBuilder log = new StringBuilder("放到下标 " + k);
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            if (a[parent] <= price) {
                log.append("，父亲 ").append(a[parent]).append(" ≤ ").append(price).append("，停");
                break;
            }
            log.append("，父亲 ").append(a[parent]).append(" > ").append(price).append(" → 父亲下移到 ").append(k);
            a[k] = a[parent];          // 不是交换，是把父亲挪下来，空位往上走
            k = parent;
        }
        a[k] = price;
        log.append("，落在 ").append(k);
        print("入 " + price, log.toString());
    }

    /** 出堆：取走堆顶，把末尾元素拿来填坑，再往下沉（sift down）。O(log n) */
    public long poll() {
        long top = a[0];
        long x = a[--size];
        int k = 0;
        StringBuilder log = new StringBuilder("取走 " + top + "，末尾 " + x + " 来填下标 0");
        int half = size >>> 1;           // 只有前一半有孩子
        while (k < half) {
            int c = 2 * k + 1, r = c + 1;
            if (r < size && a[r] < a[c]) c = r;   // 挑小的那个孩子
            if (x <= a[c]) {
                log.append("，较小的孩子 ").append(a[c]).append(" ≥ ").append(x).append("，停");
                break;
            }
            log.append("，较小的孩子 ").append(a[c]).append(" < ").append(x).append(" → 上移到 ").append(k);
            a[k] = a[c];
            k = c;
        }
        if (size > 0) a[k] = x;
        log.append("，").append(x).append(" 落在 ").append(k);
        print("出 → " + top, log.toString());
        return top;
    }

    private void print(String op, String log) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) sb.append(i == 0 ? "" : " ").append(a[i]);
        System.out.printf("%-8s [%s]%n         %s%n", op, sb, log);
    }

    public static void main(String[] args) {
        PriceHeap asks = new PriceHeap();
        for (long p : new long[]{105, 103, 108, 101, 104}) asks.offer(p);
        asks.poll();
        asks.poll();
    }
}
