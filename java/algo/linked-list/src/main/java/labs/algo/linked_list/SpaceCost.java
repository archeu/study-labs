package labs.algo.linked_list;

import org.openjdk.jol.info.GraphLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * 空间也要数：
 *   1. 同样 10 万张单，四种结构各占多少字节（JOL 顺着引用把整张对象图加起来，确定性的）。
 *   2. 额外空间：反转一条单链表，循环写法 O(1) 额外空间，递归写法 O(n) 栈空间——n 大了直接 StackOverflowError。
 */
public class SpaceCost {

    static final class Node {             // 最简单的单链表节点
        long val;
        Node next;
        Node(long val, Node next) { this.val = val; this.next = next; }
    }

    public static void main(String[] args) {
        int n = 100_000;
        long base = 1_000_000L;            // 避开 Long.valueOf 对 -128..127 的缓存

        ArrayList<Long> arr = new ArrayList<>();
        ArrayDeque<Long> deque = new ArrayDeque<>();
        LinkedList<Long> linked = new LinkedList<>();
        OrderQueue queue = new OrderQueue();
        for (int i = 0; i < n; i++) {
            Long id = base + i;
            arr.add(id); deque.add(id); linked.add(id);
            queue.add(base + i, 1);
        }

        System.out.println("== 1 十万张单，各占多少（含 Long 对象，JOL 整图统计）==");
        print("ArrayList<Long>", GraphLayout.parseInstance(arr).totalSize(), n);
        print("ArrayDeque<Long>", GraphLayout.parseInstance(deque).totalSize(), n);
        print("LinkedList<Long>", GraphLayout.parseInstance(linked).totalSize(), n);
        print("OrderQueue（双链表+索引）", GraphLayout.parseInstance(queue).totalSize(), n);
        arr.trimToSize();
        print("ArrayList 收紧容量后", GraphLayout.parseInstance(arr).totalSize(), n);

        System.out.println();
        System.out.println("== 2 反转单链表：循环 vs 递归 ==");
        for (int len : new int[]{1_000, 10_000, 100_000, 1_000_000}) {
            Node h = build(len);
            h = reverseLoop(h);
            String loop = h.val == len - 1 ? "成功" : "错了";
            String rec;
            try {
                Node r = reverseRec(build(len));
                rec = r.val == len - 1 ? "成功" : "错了";
            } catch (StackOverflowError e) {
                rec = "StackOverflowError";
            }
            System.out.printf("  n = %,9d   循环：%s   递归：%s%n", len, loop, rec);
        }
    }

    static Node build(int len) {
        Node h = null;
        for (int i = len - 1; i >= 0; i--) h = new Node(i, h);
        return h;
    }

    /** 循环：只用 prev、cur、next 三个变量，额外空间 O(1)。 */
    static Node reverseLoop(Node head) {
        Node prev = null, cur = head;
        while (cur != null) {             // 不变式：prev 是已经反好的那一段的头
            Node next = cur.next;         // ① 先记住后面
            cur.next = prev;              // ② 再掉头
            prev = cur;                   // ③ 两个指针各前进一步
            cur = next;
        }
        return prev;
    }

    /** 递归：代码短，但每个节点占一层栈帧，额外空间 O(n)。 */
    static Node reverseRec(Node head) {
        if (head == null || head.next == null) return head;
        Node newHead = reverseRec(head.next);
        head.next.next = head;
        head.next = null;
        return newHead;
    }

    static void print(String name, long bytes, int n) {
        System.out.printf("  %-24s %,12d 字节   每张 %6.1f 字节%n", name, bytes, bytes / (double) n);
    }
}
