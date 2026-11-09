package labs.javase.collections_choose;

import java.util.*;
import java.util.function.Supplier;

/**
 * 粗测：放 100 万个（共享的）Long，集合本身多占多少字节。元素对象事先建好、各集合共用，所以测到的只是容器开销。
 * java -Xms1g -Xmx1g -cp target/classes labs.javase.collections_choose.Memory
 */
public class Memory {
    static long used() {
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(100); } catch (InterruptedException e) { return -1; } }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    public static void main(String[] args) {
        int n = 1_000_000;
        Long[] items = new Long[n];
        for (int i = 0; i < n; i++) items[i] = (long) i + 1_000_000;   // 超出 Long 缓存范围，都是独立对象
        Map<String, Supplier<Collection<Long>>> kinds = new LinkedHashMap<>();
        kinds.put("ArrayList（预设容量）", () -> new ArrayList<>(n));
        kinds.put("ArrayList（默认，一路扩容）", ArrayList::new);
        kinds.put("ArrayDeque", ArrayDeque::new);
        kinds.put("LinkedList", LinkedList::new);
        kinds.put("HashSet", HashSet::new);
        kinds.put("TreeSet", TreeSet::new);
        kinds.put("PriorityQueue", PriorityQueue::new);
        for (var e : kinds.entrySet()) {
            long before = used();
            Collection<Long> c = e.getValue().get();
            Collections.addAll(c, items);
            long after = used();
            System.out.printf("%-28s 每个元素约 %5.1f 字节%n", e.getKey(), (after - before) / (double) n);
            if (c.size() != n) throw new AssertionError();
        }
        System.out.println(items.length);
    }
}
