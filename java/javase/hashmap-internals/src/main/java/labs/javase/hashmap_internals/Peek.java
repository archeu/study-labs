package labs.javase.hashmap_internals;

import java.lang.reflect.Field;
import java.util.HashMap;

/** 用反射看 HashMap 内部的桶数组。运行时要加 --add-opens java.base/java.util=ALL-UNNAMED。 */
final class Peek {
    private static final Field TABLE, NEXT, THRESHOLD;
    static {
        try {
            TABLE = HashMap.class.getDeclaredField("table");
            THRESHOLD = HashMap.class.getDeclaredField("threshold");
            NEXT = Class.forName("java.util.HashMap$Node").getDeclaredField("next");
            TABLE.setAccessible(true); THRESHOLD.setAccessible(true); NEXT.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private Peek() {}

    static int capacity(HashMap<?, ?> m) {
        try { Object[] t = (Object[]) TABLE.get(m); return t == null ? 0 : t.length; }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    static int threshold(HashMap<?, ?> m) {
        try { return THRESHOLD.getInt(m); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    static int spread(Object key) { int h = key.hashCode(); return h ^ (h >>> 16); }

    static int index(HashMap<?, ?> m, Object key) { return spread(key) & (capacity(m) - 1); }

    /** 某个桶：节点个数和节点类型（Node 是链表，TreeNode 是红黑树）。 */
    static String bucket(HashMap<?, ?> m, int idx) {
        try {
            Object[] t = (Object[]) TABLE.get(m);
            Object n = t[idx];
            if (n == null) return "空";
            int c = 0;
            for (Object p = n; p != null; p = NEXT.get(p)) c++;
            return c + " 个节点，" + n.getClass().getSimpleName();
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}
