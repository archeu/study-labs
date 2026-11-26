package labs.agent.mcp_server_build;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** 两个工具：一个只读，一个会改东西。数据放内存里，和站 04～06 是同一次故障（假数据）。 */
@Component
public class TicketTools {

    /** 返回 record：Spring AI 会据此生成 outputSchema，结果里带 structuredContent。 */
    public record Incident(String id, String title, String status, String owner, List<String> comments) {}

    private final Map<String, List<String>> comments = new ConcurrentHashMap<>(
            Map.of("INC-1108", new CopyOnWriteArrayList<>(List.of("20:16 告警：/orders/{id} p99 > 2s"))));

    @McpTool(name = "get_incident",
            description = "按工单号查故障工单：标题、状态、负责人、已有评论。排障开始时先查一下有没有人在处理。",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Incident getIncident(@McpToolParam(description = "工单号，例如 INC-1108") String id) {
        List<String> c = comments.get(id);
        if (c == null) {
            throw new IllegalArgumentException("没有工单 " + id + "，工单号形如 INC-1108");
        }
        return new Incident(id, "order-svc /orders/{id} 变慢", "处理中", "值班的你", List.copyOf(c));
    }

    @McpTool(name = "add_comment",
            description = "给故障工单加一条评论。会改工单，调用前要值班人员确认。评论写清结论和证据。",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    public String addComment(@McpToolParam(description = "工单号，例如 INC-1108") String id,
                             @McpToolParam(description = "评论正文：结论 + 证据（日志行或 GC 数字）") String text) {
        List<String> c = comments.get(id);
        if (c == null) {
            throw new IllegalArgumentException("没有工单 " + id);
        }
        c.add(LocalTime.now().withNano(0) + " " + text);
        return "已加到 " + id + "，现在共 " + c.size() + " 条评论";
    }
}
