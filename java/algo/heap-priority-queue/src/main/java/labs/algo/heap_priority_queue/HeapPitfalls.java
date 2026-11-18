package labs.algo.heap_priority_queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** ⑥ 第一圈的几个坑，每个都能在这里看到。 */
public class HeapPitfalls {
    static final class Order {
        final int id; long price;
        Order(int id, long price) { this.id = id; this.price = price; }
        @Override public String toString() { return "#" + id + "@" + price; }
    }

    static <T> String drain(PriorityQueue<T> pq) {
        List<T> out = new ArrayList<>();
        while (!pq.isEmpty()) out.add(pq.poll());
        return out.toString();
    }

    public static void main(String[] args) {
        System.out.println("== 1 打印 / for-each 不是有序的 ==");
        PriorityQueue<Integer> pq = new PriorityQueue<>();
        for (int p : new int[]{105, 103, 108, 101, 104}) pq.offer(p);
        System.out.println("  toString : " + pq);
        StringBuilder each = new StringBuilder();
        for (int p : pq) each.append(p).append(' ');
        System.out.println("  for-each : " + each.toString().trim());
        System.out.println("  逐个 poll: " + drain(pq));

        System.out.println("== 2 同价不保证先来先得（比较器里没有序号）==");
        PriorityQueue<Order> noSeq = new PriorityQueue<>(Comparator.comparingLong((Order o) -> o.price));
        for (int i = 1; i <= 8; i++) noSeq.offer(new Order(i, 100));
        System.out.println("  8 张同价单按 #1..#8 进，出来的顺序：" + drain(noSeq));

        System.out.println("== 3 比较器用减法，溢出后顺序乱了 ==");
        Comparator<Integer> bad = (a, b) -> a - b;
        PriorityQueue<Integer> pqBad = new PriorityQueue<>(bad);
        PriorityQueue<Integer> pqOk = new PriorityQueue<>(Integer::compare);
        for (int v : new int[]{2_000_000_000, -2_000_000_000, 0, 1}) { pqBad.offer(v); pqOk.offer(v); }
        System.out.println("  (a, b) -> a - b     : " + drain(pqBad));
        System.out.println("  Integer::compare    : " + drain(pqOk));
        System.out.println("  因为 2000000000 - (-2000000000) = " + (2_000_000_000 - (-2_000_000_000)));

        System.out.println("== 4 堆里的对象改了价格，堆不知道 ==");
        PriorityQueue<Order> asks = new PriorityQueue<>(Comparator.comparingLong((Order o) -> o.price));
        Order a = new Order(1, 101), b = new Order(2, 103), c = new Order(3, 105);
        asks.offer(a); asks.offer(b); asks.offer(c);
        c.price = 99;                                   // 改单：#3 改成 99，堆里没人动
        System.out.println("  #3 改成 99 之后 peek = " + asks.peek() + "   （最低价其实是 #3@99）");
        asks.remove(c); asks.offer(c);                  // 正确做法：先删再放
        System.out.println("  先 remove 再 offer 后 peek = " + asks.peek());

        System.out.println("== 5 null 放不进去 ==");
        try { new PriorityQueue<Integer>().offer(null); }
        catch (NullPointerException e) { System.out.println("  offer(null) → NullPointerException"); }
    }
}
