package labs.spring.spring_security_token;

import java.util.*;

/** 一次只发一个请求，所以用全局表记时间点和日志。 */
public final class Trace {
    public static final Map<String, Long> MARK = Collections.synchronizedMap(new LinkedHashMap<>());
    public static final List<String> EV = Collections.synchronizedList(new ArrayList<>());
    public static volatile boolean on = true;
    public static void mark(String k) { MARK.putIfAbsent(k, System.nanoTime()); }
    public static void ev(String s) { if (on) EV.add(s); }
    public static void reset() { MARK.clear(); EV.clear(); }
    private Trace() {}
}
