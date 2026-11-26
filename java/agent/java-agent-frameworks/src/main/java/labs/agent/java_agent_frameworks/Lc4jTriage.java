package labs.agent.java_agent_frameworks;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

import java.util.List;

/**
 * LangChain4j 1.24 版的排障 agent：声明一个接口，AiServices 生成实现，工具循环藏在代理里。
 * 不需要 Spring；ChatModelListener 在每次模型调用后被回调，用来数轮数。
 *
 *   mvn -q spring-boot:run -Dspring-boot.run.main-class=labs.agent.java_agent_frameworks.Lc4jTriage
 */
public class Lc4jTriage {

    interface TriageAssistant {
        @SystemMessage(OncallTools.SYSTEM)
        String ask(String question);
    }

    public static void main(String[] args) {
        int[] round = {0};
        long[] tokens = {0, 0, 0};
        ChatModelListener listener = new ChatModelListener() {
            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                var r = ctx.chatResponse();
                var u = r.tokenUsage();
                tokens[0] += u.inputTokenCount();
                tokens[1] += u.outputTokenCount();
                // inputTokenCount() 只是没命中缓存的那部分；命中的在 AnthropicTokenUsage 里单独给
                int cached = u instanceof AnthropicTokenUsage a && a.cacheReadInputTokens() != null ? a.cacheReadInputTokens() : 0;
                tokens[2] += cached;
                System.out.printf("  第 %d 轮  输入 %d（另有缓存命中 %d）输出 %d token  要了 %s%n", ++round[0], u.inputTokenCount(), cached, u.outputTokenCount(),
                        r.aiMessage().hasToolExecutionRequests()
                                ? r.aiMessage().toolExecutionRequests().stream().map(t -> t.name() + t.arguments()).toList() : "[]");
            }
        };
        AnthropicChatModel model = AnthropicChatModel.builder()
                .baseUrl(System.getenv().getOrDefault("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090/v1/"))
                .apiKey(System.getenv().getOrDefault("ANTHROPIC_API_KEY", "local"))
                .modelName(System.getenv().getOrDefault("MODEL", "qwen3-1.7b"))
                .maxTokens(700).temperature(0.0)
                .listeners(List.of(listener))
                .build();
        TriageAssistant assistant = AiServices.builder(TriageAssistant.class)
                .chatModel(model)
                .tools(new OncallTools())
                .build();
        long t0 = System.nanoTime();
        String answer = assistant.ask(OncallTools.QUESTION);
        System.out.println("\n=== LangChain4j 结论 ===\n" + answer);
        System.out.printf("%n工具调用 %d 次，累计输入 %d（另有缓存命中 %d）输出 %d token，用时 %.1fs%n",
                OncallTools.CALLS.get(), tokens[0], tokens[2], tokens[1], (System.nanoTime() - t0) / 1e9);
    }
}
