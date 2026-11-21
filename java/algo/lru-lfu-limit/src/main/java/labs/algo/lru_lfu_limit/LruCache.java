package labs.algo.lru_lfu_limit;

import java.util.HashMap;
import java.util.Map;

/**
 * 手写 LRU：HashMap 找节点 O(1)，双向链表维护“最近用过”的顺序 O(1)。
 * head 后面是最近用的，tail 前面是最久没用的；两个哨兵省掉所有判空（第 4 课）。
 * 非线程安全：get 也会改链表，多线程要整体加锁。
 */
public class LruCache<K, V> {
    static final class Node<K, V> {
        K key; V val; Node<K, V> prev, next;
        Node(K k, V v) { key = k; val = v; }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<K, Node<K, V>>();
    private final Node<K, V> head = new Node<K, V>(null, null), tail = new Node<K, V>(null, null);
    int evictions;

    public LruCache(int capacity) {
        this.capacity = capacity;
        head.next = tail; tail.prev = head;
    }

    public V get(K key) {
        Node<K, V> n = map.get(key);
        if (n == null) return null;
        unlink(n); addFirst(n);              // 读也要挪位置：读就是写
        return n.val;
    }

    public void put(K key, V val) {
        Node<K, V> n = map.get(key);
        if (n != null) { n.val = val; unlink(n); addFirst(n); return; }
        if (map.size() == capacity) {
            Node<K, V> old = tail.prev;      // 最久没用的
            unlink(old); map.remove(old.key); evictions++;
        }
        n = new Node<K, V>(key, val);
        map.put(key, n); addFirst(n);
    }

    private void unlink(Node<K, V> n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void addFirst(Node<K, V> n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (Node<K, V> n = head.next; n != tail; n = n.next) sb.append(n.key).append(n.next == tail ? "" : ", ");
        return sb.append("]").toString();
    }

    public static void main(String[] args) {
        LruCache<String, Integer> c = new LruCache<String, Integer>(3);
        c.put("BTC", 1); c.put("ETH", 2); c.put("SOL", 3);
        System.out.println("put BTC ETH SOL   → " + c);
        c.get("BTC");
        System.out.println("get BTC           → " + c);
        c.put("DOGE", 4);
        System.out.println("put DOGE（踢 ETH）→ " + c);
        c.get("SOL");
        System.out.println("get SOL           → " + c);
        c.put("XRP", 5);
        System.out.println("put XRP（踢 BTC） → " + c + "，get ETH = " + c.get("ETH"));
    }
}
