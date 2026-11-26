package labs.agent.java_agent_graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 全景那张图的 Java 版，用 Spring AI Alibaba Graph 1.1.2.3：triage → logs/metrics/notes 并行 → summarize → （停下）→ act。
 * 和 LangGraph 一一对应：StateGraph、KeyStrategy（= reducer）、MemorySaver（= checkpointer）、interruptBefore、threadId。
 * 节点里不调模型（sleep 模拟模型耗时），只看框架那一侧。
 *
 *   mvn -q -pl agent/java-agent-graph compile exec:java
 */
public class GraphTriage {

    static long t0 = System.nanoTime();

    static void log(String msg) {
        System.out.printf("%6.2fs  [%s] %s%n", (System.nanoTime() - t0) / 1e9, Thread.currentThread().getName(), msg);
    }

    static Map<String, Object> investigate(String name, long millis, String finding) throws InterruptedException {
        log("investigate[" + name + "] 开始");
        Thread.sleep(millis);
        log("investigate[" + name + "] 完成");
        return Map.of("findings", List.of(name + "：" + finding));
    }

    public static void main(String[] args) throws Exception {
        StateGraph graph = new StateGraph(() -> {
            Map<String, KeyStrategy> keys = new HashMap<>();
            keys.put("alert", new ReplaceStrategy());
            keys.put("findings", new AppendStrategy());          // 三个并行节点都往这里写：追加，= LangGraph 的 operator.add
            keys.put("conclusion", new ReplaceStrategy());
            keys.put("approval", new ReplaceStrategy());
            keys.put("ticket", new ReplaceStrategy());
            return keys;
        });
        graph.addNode("triage", node_async(s -> { Thread.sleep(600); log("triage 分诊：派 3 个子 agent"); return Map.of(); }))
             .addNode("logs", node_async(s -> investigate("logs", 1200, "20:00 上线导出，一次读 412,883 行")))
             .addNode("metrics", node_async(s -> investigate("metrics", 900, "Full GC 3 次，每次约 1.9s")))
             .addNode("notes", node_async(s -> investigate("notes", 700, "《G1 和 ZGC 怎么回收》")))
             .addNode("summarize", node_async(s -> {
                 List<?> f = s.value("findings", List.class).orElse(List.of());
                 log("summarize 汇总 " + f.size() + " 段结论");
                 return Map.of("conclusion", "导出接口一次读 41 万行，老年代打满，Full GC");
             }))
             .addNode("act", node_async(s -> {
                 log("act 执行：批准人 = " + s.value("approval").orElse("（没有）"));
                 return Map.of("ticket", "INC-1108");
             }))
             .addEdge(StateGraph.START, "triage")
             .addEdge("triage", List.of("logs", "metrics", "notes"))      // 扇出：三条边从同一个节点出去
             .addEdge(List.of("logs", "metrics", "notes"), "summarize")   // 汇合
             .addEdge("summarize", "act")
             .addEdge("act", StateGraph.END);

        CompiledGraph app = graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .interruptBefore("act")                                    // 写操作之前停下
                .build());

        // 并行节点默认跑在框架自己建的线程池上，那些线程不是守护线程：main 结束了 JVM 也不退出（⑥ 坑 4）。
        // 自己给一个线程池，用完关掉。
        ExecutorService pool = Executors.newFixedThreadPool(3);
        RunnableConfig cfg = RunnableConfig.builder().threadId("alert-7f3a").defaultParallelExecutor(pool).build();
        OverAllState s1 = app.invoke(Map.of("alert", "OrderApiP99High 7f3a"), cfg).orElseThrow();
        log("⏸ 停下：findings " + s1.value("findings", List.class).map(List::size).orElse(0) + " 段，下一步 = "
                + app.getState(cfg).next());

        // 张三批准：把批准结果写进 state，再接着跑
        RunnableConfig resumed = app.updateState(cfg, Map.of("approval", "张三 20:31"));
        OverAllState s2 = app.invoke(Map.of(), RunnableConfig.builder().threadId("alert-7f3a").defaultParallelExecutor(pool).resume().build()).orElseThrow();
        log("结束：ticket = " + s2.value("ticket").orElse("（没有）") + "，检查点 " + app.getStateHistory(cfg).size() + " 个");
        pool.shutdown();
    }
}
