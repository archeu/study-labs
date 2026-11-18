package labs.algo.stack_queue_ring;

/**
 * 撮合入口的定长环形队列：一块数组首尾相接，入队、出队都是 O(1)，不扩容、不分配新对象。
 *
 * <p>空和满怎么分：head、tail 都是只增不减的 long 序号，不回绕；
 * 下标 = 序号 & (容量 - 1)，所以容量必须是 2 的幂。
 * size = tail - head，空是 size == 0，满是 size == 容量，两者再也不会撞在一起。
 * （ArrayDeque 用“永远留一个空格”，ArrayBlockingQueue 用 count 字段，Disruptor 用的就是这里的序号。）
 *
 * <p>main 跑的是页面 ⑤ 手推的那一段：容量 4，绕一圈再撞满。
 */
public class OrderRing {
    private final long[] slots;   // 存订单号；用 long[] 不装箱，整个队列只有这一块数组
    private final int mask;
    private long head;            // 下一个要取的序号
    private long tail;            // 下一个要放的序号

    public OrderRing(int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("容量要是 2 的幂：" + capacity);
        }
        slots = new long[capacity];
        mask = capacity - 1;
    }

    /** 入队到队尾；满了返回 false，交给调用方决定等、丢还是告诉上游慢一点。 */
    public boolean offer(long orderId) {
        if (tail - head == slots.length) return false;
        slots[(int) (tail & mask)] = orderId;
        tail++;
        check();
        return true;
    }

    /** 从队头取；空了返回 -1。 */
    public long poll() {
        if (tail == head) return -1;
        int i = (int) (head & mask);
        long id = slots[i];
        slots[i] = 0;             // 页面上看得清；真实实现存对象时这一步是为了让 GC 能回收
        head++;
        check();
        return id;
    }

    public int size() { return (int) (tail - head); }

    /** 不变式：0 <= size <= 容量。每步都查，推错了直接抛异常。 */
    private void check() {
        long n = tail - head;
        if (n < 0 || n > slots.length) throw new IllegalStateException("不变式被破坏：size=" + n);
    }

    String show() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(slots[i] == 0 ? " ·  " : "#" + slots[i]);
        }
        return sb.append("]  head=").append(head).append("→槽").append(head & mask)
                 .append("  tail=").append(tail).append("→槽").append(tail & mask)
                 .append("  size=").append(size()).toString();
    }

    public static void main(String[] args) {
        OrderRing q = new OrderRing(4);
        line("空队列", q);
        q.offer(101); q.offer(102); q.offer(103);
        line("入 #101 #102 #103", q);
        q.poll(); q.poll();
        line("出 2 张（#101 #102）", q);
        q.offer(104); q.offer(105);
        line("入 #104 #105（绕回槽 0）", q);
        q.offer(106);
        line("入 #106", q);
        boolean ok = q.offer(107);
        line("入 #107 → " + (ok ? "成功" : "满了，拒绝"), q);
        StringBuilder out = new StringBuilder();
        long id;
        while ((id = q.poll()) != -1) out.append('#').append(id).append(' ');
        line("全部出队：" + out.toString().trim(), q);
    }

    private static void line(String what, OrderRing q) {
        System.out.printf("%-22s %s%n", what, q.show());
    }
}
