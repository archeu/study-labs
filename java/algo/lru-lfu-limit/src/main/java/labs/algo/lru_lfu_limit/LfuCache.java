package labs.algo.lru_lfu_limit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 手写 LFU，三个操作都 O(1)：
 * - vals / freq：key → 值、key → 被访问次数
 * - buckets：次数 → 这个次数的 key（LinkedHashSet 保留先来后到，同次数时踢最早的）
 * - minFreq：当前最小次数；新 key 进来一定是 1，访问只会让某个 key 的次数 +1，所以 minFreq 不用找，能直接维护
 */
public class LfuCache<K, V> {
    private final int capacity;
    private final Map<K, V> vals = new HashMap<K, V>();
    private final Map<K, Integer> freq = new HashMap<K, Integer>();
    private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<Integer, LinkedHashSet<K>>();
    private int minFreq;
    int evictions;

    public LfuCache(int capacity) { this.capacity = capacity; }

    public V get(K key) {
        if (!vals.containsKey(key)) return null;
        touch(key);
        return vals.get(key);
    }

    public void put(K key, V val) {
        if (capacity == 0) return;
        if (vals.containsKey(key)) { vals.put(key, val); touch(key); return; }
        if (vals.size() == capacity) {
            LinkedHashSet<K> b = buckets.get(minFreq);
            Iterator<K> it = b.iterator();
            K old = it.next(); it.remove();          // 次数最少里最早来的
            if (b.isEmpty()) buckets.remove(minFreq);
            vals.remove(old); freq.remove(old); evictions++;
        }
        vals.put(key, val); freq.put(key, 1);
        bucket(1).add(key);
        minFreq = 1;
    }

    private void touch(K key) {
        int f = freq.get(key);
        LinkedHashSet<K> b = buckets.get(f);
        b.remove(key);
        if (b.isEmpty()) { buckets.remove(f); if (minFreq == f) minFreq = f + 1; }
        freq.put(key, f + 1);
        bucket(f + 1).add(key);
    }

    private LinkedHashSet<K> bucket(int f) {
        LinkedHashSet<K> b = buckets.get(f);
        if (b == null) { b = new LinkedHashSet<K>(); buckets.put(f, b); }
        return b;
    }

    @Override public String toString() { return "freq=" + freq + " minFreq=" + minFreq; }

    public static void main(String[] args) {
        LfuCache<String, Integer> c = new LfuCache<String, Integer>(3);
        c.put("BTC", 1); c.put("ETH", 2); c.put("SOL", 3);
        c.get("BTC"); c.get("BTC"); c.get("ETH");
        System.out.println("BTC 读 2 次、ETH 读 1 次 → " + c);
        c.put("DOGE", 4);
        System.out.println("put DOGE（踢次数最少的 SOL）→ " + c);
        c.put("XRP", 5);
        System.out.println("put XRP（DOGE 只有 1 次，被踢）→ " + c);
    }
}
