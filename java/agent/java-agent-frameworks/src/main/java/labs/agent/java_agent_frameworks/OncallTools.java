package labs.agent.java_agent_frameworks;

import dev.langchain4j.agent.tool.P;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站 04 的三个只读工具，Java 版。现场数据和 agent/pano-04-triage-agent/oncall.py 一样（2026-11-08 20:16 order-04 变慢）。
 * 每个方法同时带 Spring AI 和 LangChain4j 的 @Tool：两家都是“方法签名 + 描述 → JSON Schema 说明书”，注解长得几乎一样。
 */
public class OncallTools {

    public static final String SYSTEM = """
            你是 order-svc 的值班排障助手。值班同学会问你某个告警的原因。
            排查顺序（值班 SOP）：
            1. 先看这段时间的报错和慢请求日志，以及有没有上线；
            2. 怀疑 GC 时解析 GC 日志：Full GC 次数、停顿多长、回收后老年代剩多少；
            3. 需要时看线程栈，确认线程卡在哪；
            4. 给结论：原因、证据（引用工具返回的原文数字）、建议的下一步。
            只用工具拿到的事实，不要猜数字。""";
    public static final String QUESTION = "20:16 告警：order-04 接口 P99 2.1 秒（阈值 800ms）。order-svc 为什么变慢了？";

    static final String[] APP_LOG = {
        "2026-11-08 20:00:05.112 INFO  order-svc  [main] ExportController 上线：/orders/export（按月导出订单）",
        "2026-11-08 20:09:41.630 WARN  order-svc  [http-nio-8080-exec-37] ExportService 导出 2026-10 订单 412,883 行，全部载入内存后写 Excel",
        "2026-11-08 20:11:58.004 WARN  order-svc  [http-nio-8080-exec-12] ExportService 导出 2026-10 订单 412,883 行，全部载入内存后写 Excel",
        "2026-11-08 20:12:45.090 WARN  order-svc  [http-nio-8080-exec-88] OrderController /orders/{id} 耗时 2104ms",
        "2026-11-08 20:13:10.511 ERROR order-svc  [http-nio-8080-exec-91] OrderController /orders/{id} 耗时 2387ms，客户端已断开",
        "2026-11-08 20:14:02.377 WARN  order-svc  [http-nio-8080-exec-40] ExportService 导出 2026-10 订单 412,883 行，全部载入内存后写 Excel",
        "2026-11-08 20:15:30.020 WARN  order-svc  [http-nio-8080-exec-07] OrderController /orders/{id} 耗时 1951ms"};
    static final String[] GC_LOG = {
        "[2026-11-08T20:09:58.101+0800][info][gc] GC(1209) Pause Young (Normal) (G1 Evacuation Pause) 388M->176M(1024M) 41.220ms",
        "[2026-11-08T20:11:20.318+0800][info][gc] GC(1211) Pause Young (Concurrent Start) (G1 Humongous Allocation) 760M->690M(1024M) 188.940ms",
        "[2026-11-08T20:12:03.512+0800][info][gc] GC(1213) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 236.871ms",
        "[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms",
        "[2026-11-08T20:13:10.033+0800][info][gc] GC(1215) Pause Young (Normal) (G1 Evacuation Pause) 905M->711M(1024M) 198.204ms",
        "[2026-11-08T20:14:20.640+0800][info][gc] GC(1218) Pause Full (G1 Compaction Pause) 1018M->733M(1024M) 1920.008ms",
        "[2026-11-08T20:15:31.902+0800][info][gc] GC(1221) Pause Full (G1 Compaction Pause) 1020M->741M(1024M) 1966.375ms"};
    static final String THREAD_DUMP = """
            "http-nio-8080-exec-40" RUNNABLE  at org.apache.poi.xssf.usermodel.XSSFSheet.createRow / at com.demo.order.ExportService.writeExcel(ExportService.java:88)
            "http-nio-8080-exec-12" RUNNABLE  at java.util.ArrayList.grow / at com.demo.order.ExportService.loadAll(ExportService.java:51)
            "http-nio-8080-exec-88" TIMED_WAITING at com.zaxxer.hikari.pool.HikariPool.getConnection (1 个)
            其余 197 个 http-nio 线程 WAITING（空闲）""";

    public static final AtomicInteger CALLS = new AtomicInteger();
    private static final Pattern HHMM = Pattern.compile("(\\d\\d:\\d\\d):\\d\\d");

    private static boolean after(String line, String since) {
        Matcher m = HHMM.matcher(line);
        return since == null || since.isBlank() || !m.find() || m.group(1).compareTo(since) >= 0;
    }

    @Tool(description = "在 order-svc 应用日志里按正则查某段时间的日志行，查报错、慢请求、上线记录时用。返回最多 20 行。")
    @dev.langchain4j.agent.tool.Tool("在 order-svc 应用日志里按正则查某段时间的日志行，查报错、慢请求、上线记录时用。返回最多 20 行。")
    public String searchLogs(
            @ToolParam(description = "正则，例如 WARN|ERROR、耗时、Export") @P("正则，例如 WARN|ERROR、耗时、Export") String keyword,
            @ToolParam(description = "开始时间 HH:MM，告警前 15 分钟，例如告警 20:16 就填 20:00") @P("开始时间 HH:MM，告警前 15 分钟，例如告警 20:16 就填 20:00") String since) {
        CALLS.incrementAndGet();
        List<String> rows = new ArrayList<>();
        Pattern p = Pattern.compile(keyword);
        for (String l : APP_LOG) if (p.matcher(l).find() && after(l, since)) rows.add(l);
        String out = rows.isEmpty() ? "没有匹配的日志" : String.join("\n", rows.subList(0, Math.min(20, rows.size())));
        System.out.printf("    [tool] searchLogs(%s, %s) → %d 字%n", keyword, since, out.length());
        return out;
    }

    @Tool(description = "解析 order-svc 的 GC 日志：停顿次数、最长停顿、Full GC 次数、Full GC 后堆还占多少。怀疑 GC 导致变慢时用。")
    @dev.langchain4j.agent.tool.Tool("解析 order-svc 的 GC 日志：停顿次数、最长停顿、Full GC 次数、Full GC 后堆还占多少。怀疑 GC 导致变慢时用。")
    public String parseGcLog(
            @ToolParam(description = "开始时间 HH:MM，告警前 15 分钟") @P("开始时间 HH:MM，告警前 15 分钟") String since) {
        CALLS.incrementAndGet();
        double max = 0, fullSum = 0;
        int n = 0, full = 0;
        List<String> after = new ArrayList<>();
        boolean humongous = false;
        Pattern ms = Pattern.compile("([\\d.]+)ms$"), heap = Pattern.compile("->(\\d+)M\\((\\d+)M\\)");
        for (String l : GC_LOG) {
            if (!after(l, since)) continue;
            n++;
            Matcher m = ms.matcher(l);
            m.find();
            double v = Double.parseDouble(m.group(1));
            max = Math.max(max, v);
            if (l.contains("Pause Full")) {
                full++;
                fullSum += v;
                Matcher h = heap.matcher(l);
                h.find();
                after.add(Integer.parseInt(h.group(1)) * 100 / Integer.parseInt(h.group(2)) + "%");
            }
            humongous |= l.contains("Humongous");
        }
        String out = n == 0 ? "这段时间没有 GC 记录"
                : String.format("%d 次停顿，最长 %.0fms；Full GC %d 次%s%s", n, max, full,
                full > 0 ? String.format("（每次约 %.1fs），Full GC 后堆仍占 %s", fullSum / full / 1000, String.join(", ", after)) : "",
                humongous ? "；有 Humongous Allocation（大对象）" : "");
        System.out.printf("    [tool] parseGcLog(%s) → %d 字%n", since, out.length());
        return out;
    }

    @Tool(description = "对 order-svc 做一次线程栈快照（jstack），返回按状态归类的摘要。看线程卡在哪时用。")
    @dev.langchain4j.agent.tool.Tool("对 order-svc 做一次线程栈快照（jstack），返回按状态归类的摘要。看线程卡在哪时用。")
    public String threadDump() {
        CALLS.incrementAndGet();
        System.out.printf("    [tool] threadDump() → %d 字%n", THREAD_DUMP.length());
        return THREAD_DUMP;
    }
}
