package labs.spring.spring_mvc_dispatch;

import jakarta.servlet.ServletRequest;
import java.util.*;

/** 一次只发一个请求，所以用全局列表记“谁在哪种分派里出场”，用全局数组记时间点。 */
public final class Trace {
    public static final List<String> EV = Collections.synchronizedList(new ArrayList<>());
    public static final Map<String, Long> MARK = new LinkedHashMap<>();
    public static volatile boolean on = true;

    public static void ev(ServletRequest req, String who, String what) {
        if (on) EV.add(String.format("%-8s %-22s %s", "[" + req.getDispatcherType() + "]", who, what));
    }
    public static void mark(String k) { MARK.putIfAbsent(k, System.nanoTime()); }
    public static void reset() { EV.clear(); MARK.clear(); }
    private Trace() {}
}
