package labs.java.pano_j01_order_object_life;

import java.util.ArrayList;
import java.util.List;

/** 一次请求里按站记录：距请求进来过了多少微秒、这一站看到了什么。 */
public final class Trace {
    private static final ThreadLocal<Long> START = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> LINES = ThreadLocal.withInitial(ArrayList::new);

    private Trace() {}

    public static void begin() {
        START.set(System.nanoTime());
        LINES.get().clear();
    }

    public static void mark(int stop, String what) {
        Long s = START.get();
        long us = s == null ? -1 : (System.nanoTime() - s) / 1_000;
        LINES.get().add(String.format("  [%2d] %7d us  %s", stop, us, what));
    }

    public static List<String> end() {
        List<String> out = List.copyOf(LINES.get());
        LINES.remove();
        START.remove();
        return out;
    }
}
