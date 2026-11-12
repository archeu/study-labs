package labs.spring.spring_boot_bean_ledger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 各站把自己看到的东西记在这里，主程序最后统一打印。 */
public final class Trace {
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    public static final Map<String, Object> FACTS = new ConcurrentHashMap<>();
    public static volatile boolean on = true;

    public static void ev(String station, String what) {
        if (on) EVENTS.add(String.format("[%-26s] %-9s %s", Thread.currentThread().getName(), station, what));
    }
    /** 计时点：热身后的请求是一个接一个发的，所以用一张静态表就够了。 */
    public static final Map<String, Long> MARK = new ConcurrentHashMap<>();
    public static void mark(String k) { MARK.put(k, System.nanoTime()); }
    private Trace() {}
}
