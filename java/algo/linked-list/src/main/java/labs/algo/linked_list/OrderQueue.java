package labs.algo.linked_list;

import java.util.HashMap;
import java.util.Map;

/**
 * 一个价位上的挂单队列：先来先成交（从头取），新单排到尾，撤单从中间删。
 * 双向链表 + 两个哨兵 + HashMap 索引：三种操作都是 O(1)。
 *
 * 不变式（每次操作前后都成立，checkInvariants() 会检查）：
 *   1. head.next 是第一张真单，tail.prev 是最后一张；没有单时 head.next == tail。
 *   2. 对每个节点 x：x.next.prev == x，x.prev.next == x。
 *   3. index 里的单和链表里的单是同一批，个数等于 size。
 */
public class OrderQueue {

    static final class Node {
        final long id;
        final int qty;
        Node prev, next;
        Node(long id, int qty) { this.id = id; this.qty = qty; }
    }

    private final Node head = new Node(-1, 0);   // 哨兵：不放数据，永远在最前
    private final Node tail = new Node(-1, 0);   // 哨兵：永远在最后
    private final Map<Long, Node> index = new HashMap<>();
    private int size;

    public OrderQueue() {
        head.next = tail;
        tail.prev = head;
    }

    /** 新单排到队尾：O(1)。 */
    public void add(long id, int qty) {
        Node x = new Node(id, qty);
        Node p = tail.prev;          // 原来的最后一张（或者 head）
        x.prev = p;                  // ① 先让新节点指好两边
        x.next = tail;
        p.next = x;                  // ② 再让两边指向它
        tail.prev = x;
        index.put(id, x);
        size++;
    }

    /** 撤单：先查索引拿到节点，再改两根指针。O(1)。 */
    public boolean cancel(long id) {
        Node x = index.remove(id);
        if (x == null) return false;
        unlink(x);
        return true;
    }

    /** 成交：取队头那一张。O(1)。没有单返回 null。 */
    public Node pollFirst() {
        Node x = head.next;
        if (x == tail) return null;
        index.remove(x.id);
        unlink(x);
        return x;
    }

    private void unlink(Node x) {
        x.prev.next = x.next;        // 有哨兵，prev 和 next 永远不是 null，不用分头尾
        x.next.prev = x.prev;
        x.prev = x.next = null;      // 断开旧节点，见页面 ⑥ 坑 5
        size--;
    }

    public int size() { return size; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("head");
        for (Node x = head.next; x != tail; x = x.next) sb.append(" ⇄ #").append(x.id);
        return sb.append(" ⇄ tail").toString();
    }

    String backwards() {
        StringBuilder sb = new StringBuilder("tail");
        for (Node x = tail.prev; x != head; x = x.prev) sb.append(" ⇄ #").append(x.id);
        return sb.append(" ⇄ head").toString();
    }

    void checkInvariants() {
        int n = 0;
        for (Node x = head.next; x != tail; x = x.next) {
            if (x.next.prev != x || x.prev.next != x) throw new IllegalStateException("指针不对称：#" + x.id);
            if (index.get(x.id) != x) throw new IllegalStateException("索引和链表不一致：#" + x.id);
            n++;
        }
        if (n != size || index.size() != size) throw new IllegalStateException("个数不一致：链表 " + n + "，索引 " + index.size() + "，size " + size);
    }

    public static void main(String[] args) {
        OrderQueue q = new OrderQueue();
        step(q, "空队列");
        for (long id = 101; id <= 105; id++) q.add(id, 10);
        step(q, "挂 5 张单 #101～#105");
        q.cancel(103);
        step(q, "撤 #103（中间）");
        Node f = q.pollFirst();
        step(q, "成交 #" + f.id + "（队头）");
        q.cancel(105);
        step(q, "撤 #105（队尾）");
        q.add(106, 10);
        step(q, "挂 #106");
        while (q.pollFirst() != null) { }
        step(q, "全部成交");
        System.out.println("反着走一遍也对得上，说明每根 prev 都改对了：");
        q.add(201, 1); q.add(202, 1); q.add(203, 1); q.cancel(202);
        System.out.println("  正着 " + q);
        System.out.println("  反着 " + q.backwards());
    }

    private static void step(OrderQueue q, String what) {
        q.checkInvariants();
        System.out.printf("%-16s size=%d  %s%n", what, q.size(), q);
    }
}
