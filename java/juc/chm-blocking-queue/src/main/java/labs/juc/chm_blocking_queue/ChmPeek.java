package labs.juc.chm_blocking_queue;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用反射看 ConcurrentHashMap 里面：table 多长、sizeCtl、某个桶里是什么节点、计数器拆成了几格。
 * 需要：--add-opens java.base/java.util.concurrent=ALL-UNNAMED
 */
public class ChmPeek {
    /** 故意让所有 key 的 hashCode 一样：全挤进一个桶。实现 Comparable，树化后能按大小排。 */
    static final class BadSku implements Comparable<BadSku> {
        final int id;
        BadSku(int id) { this.id = id; }
        @Override public int hashCode() { return 88; }
        @Override public boolean equals(Object o) { return o instanceof BadSku && ((BadSku) o).id == id; }
        @Override public int compareTo(BadSku o) { return Integer.compare(id, o.id); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. 扩容：table 长度和 sizeCtl ==");
        ConcurrentHashMap<Long, Long> m = new ConcurrentHashMap<>();
        System.out.println("刚 new：        " + shape(m));
        for (long i = 1; i <= 100; i++) {
            m.put(i, i);
            if (i == 1 || i == 12 || i == 13 || i == 24 || i == 25 || i == 48 || i == 49 || i == 100)
                System.out.printf("放了 %3d 个：  %s%n", i, shape(m));
        }

        System.out.println("\n== 2. 一个桶里挤满：链表 → 树 ==");
        ConcurrentHashMap<BadSku, Integer> bad = new ConcurrentHashMap<>(64);   // 表长 ≥ 64 才会树化
        for (int i = 1; i <= 10; i++) {
            bad.put(new BadSku(i), i);
            if (i == 8 || i == 9) System.out.printf("同一个桶里 %d 个：%s%n", i, bin(bad, 88));
        }

        System.out.println("\n== 3. 计数器：两个线程同时 put，baseCount 和 counterCells ==");
        final ConcurrentHashMap<Long, Long> c = new ConcurrentHashMap<>(1 << 20);
        Thread a = new Thread(() -> { for (long i = 0; i < 300_000; i++) c.put(i, i); });
        Thread b = new Thread(() -> { for (long i = 1_000_000; i < 1_300_000; i++) c.put(i, i); });
        a.start(); b.start(); a.join(); b.join();
        Object[] cells = (Object[]) get(c, "counterCells");
        long base = (Long) get(c, "baseCount");
        long inCells = 0;
        if (cells != null) for (Object cell : cells) if (cell != null) inCells += (Long) get(cell, "value");
        System.out.printf("size()=%d，baseCount=%d，counterCells %s，格子里合计 %d%n", c.size(), base,
                cells == null ? "没有" : cells.length + " 格", inCells);

        System.out.println("\n== 4. computeIfAbsent 里再改同一个 map ==");
        ConcurrentHashMap<String, Integer> r = new ConcurrentHashMap<>();
        try {
            // "AaAa" 和 "BBBB" 的 hashCode 相同，落在同一个桶
            r.computeIfAbsent("AaAa", k -> r.computeIfAbsent("BBBB", k2 -> 2) + 1);
            System.out.println("没出事：" + r);
        } catch (IllegalStateException e) {
            System.out.println("抛 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static String shape(ConcurrentHashMap<?, ?> m) throws Exception {
        Object[] tab = (Object[]) get(m, "table");
        return "table " + (tab == null ? "还没建" : "长 " + tab.length) + "，sizeCtl=" + get(m, "sizeCtl");
    }

    static String bin(ConcurrentHashMap<?, ?> m, int hash) throws Exception {
        Object[] tab = (Object[]) get(m, "table");
        int h = (hash ^ (hash >>> 16)) & 0x7fffffff;
        Object head = tab[h & (tab.length - 1)];
        return "表长 " + tab.length + "，桶 " + (h & (tab.length - 1)) + " 的头节点是 " + head.getClass().getSimpleName();
    }

    static Object get(Object o, String name) throws Exception {
        Class<?> k = o.getClass();
        while (true) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
                if (k == null) throw e;
            }
        }
    }
}
