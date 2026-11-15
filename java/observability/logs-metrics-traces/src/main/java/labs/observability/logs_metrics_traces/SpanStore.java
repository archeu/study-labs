package labs.observability.logs_metrics_traces;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 代替“链路库”（Tempo / Jaeger / SkyWalking）：span 一结束就收进内存，按 trace_id 分组。
 * Spring Boot 会把容器里所有 SpanProcessor bean 交给 OpenTelemetry SDK。
 */
@Component
public class SpanStore implements SpanProcessor {

    private final Map<String, List<SpanData>> byTrace = new ConcurrentHashMap<>();

    @Override public void onStart(Context parent, ReadWriteSpan span) { }
    @Override public boolean isStartRequired() { return false; }
    @Override public boolean isEndRequired() { return true; }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanData d = span.toSpanData();
        byTrace.computeIfAbsent(d.getTraceId(), k -> new CopyOnWriteArrayList<>()).add(d);
    }

    /** 画成瀑布图：缩进表示父子，条的位置和长度按时间比例。 */
    public String render(String traceId) {
        List<SpanData> spans = byTrace.getOrDefault(traceId, List.of());
        if (spans.isEmpty()) return "没有这个 trace：" + traceId + "\n";
        long t0 = spans.stream().mapToLong(SpanData::getStartEpochNanos).min().getAsLong();
        long t1 = spans.stream().mapToLong(SpanData::getEndEpochNanos).max().getAsLong();
        double total = Math.max(1, t1 - t0);
        StringBuilder sb = new StringBuilder("trace_id " + traceId + "  共 " + spans.size() + " 个 span\n");
        List<SpanData> roots = new ArrayList<>();
        for (SpanData s : spans) {
            boolean hasParentHere = spans.stream().anyMatch(p -> p.getSpanId().equals(s.getParentSpanId()));
            if (!hasParentHere) roots.add(s);
        }
        roots.sort(Comparator.comparingLong(SpanData::getStartEpochNanos));
        for (SpanData r : roots) walk(r, spans, 0, t0, total, sb);
        return sb.toString();
    }

    private void walk(SpanData s, List<SpanData> all, int depth, long t0, double total, StringBuilder sb) {
        int width = 40;
        int from = (int) Math.round((s.getStartEpochNanos() - t0) / total * width);
        int len = Math.max(1, (int) Math.round((s.getEndEpochNanos() - s.getStartEpochNanos()) / total * width));
        String bar = " ".repeat(from) + "█".repeat(Math.min(len, width - from));
        String label = "  ".repeat(depth) + s.getName() + "  [" + s.getKind() + "]";
        sb.append(String.format("%-46s %-40s %7.1f ms  span=%s parent=%s%n", label, bar,
                (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1e6, s.getSpanId(),
                s.getParentSpanContext().isValid() ? s.getParentSpanId() : "-"));
        all.stream().filter(c -> c.getParentSpanId().equals(s.getSpanId()))
                .sorted(Comparator.comparingLong(SpanData::getStartEpochNanos))
                .forEach(c -> walk(c, all, depth + 1, t0, total, sb));
    }
}
