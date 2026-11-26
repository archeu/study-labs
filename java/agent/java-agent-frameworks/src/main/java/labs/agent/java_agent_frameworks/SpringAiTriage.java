package labs.agent.java_agent_frameworks;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI 2.1 版的排障 agent：ChatClient + @Tool + Advisor 链。
 * ToolCallingAdvisor 把“调模型 → 调工具 → 再调模型”的循环放进 Advisor 链里，
 * 排在它里面的 RoundLogger 每一轮都会被调到（像 AOP 的环绕通知，切在每次模型调用上）。
 *
 *   mvn -q spring-boot:run -Dspring-boot.run.main-class=labs.agent.java_agent_frameworks.SpringAiTriage
 *   mvn -q spring-boot:run -Dspring-boot.run.main-class=labs.agent.java_agent_frameworks.SpringAiTriage -Dspring-boot.run.arguments=internal
 *     （internal：不挂 ToolCallingAdvisor，工具循环在 ChatModel 内部转，看 RoundLogger 还能看到几轮）
 */
@SpringBootApplication
public class SpringAiTriage {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiTriage.class, args);
    }

    /** 每一轮模型调用记一行：要了哪些工具、用了多少 token、花了多久。 */
    static class RoundLogger implements CallAdvisor {
        int round;

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            long t0 = System.nanoTime();
            ChatClientResponse resp = chain.nextCall(request);
            ChatResponse r = resp.chatResponse();
            var out = r.getResult().getOutput();
            var u = r.getMetadata().getUsage();
            System.out.printf("  第 %d 轮 %5.1fs  输入 %d 输出 %d token  要了 %s%n", ++round, (System.nanoTime() - t0) / 1e9,
                    u.getPromptTokens(), u.getCompletionTokens(),
                    out.getToolCalls().stream().map(tc -> tc.name() + tc.arguments()).toList());
            if (round == 1) System.out.println("    （原始 usage：" + u.getNativeUsage() + "）");
            return resp;
        }

        @Override
        public String getName() { return "RoundLogger"; }

        @Override
        public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER + 1; }   // 数字更大 = 更靠里，在工具循环里面
    }

    @Bean
    CommandLineRunner run(ChatClient.Builder builder) {
        return args -> {
            boolean internal = java.util.Arrays.asList(args).contains("internal");
            ChatClient.Builder b = builder.defaultSystem(OncallTools.SYSTEM).defaultTools(new OncallTools());
            ChatClient client = internal
                    ? b.defaultAdvisors(new RoundLogger()).build()                                   // 循环藏在 ChatModel 里
                    : b.defaultAdvisors(ToolCallingAdvisor.builder().build(), new RoundLogger()).build();  // 循环在 Advisor 链里
            System.out.println(internal ? "不挂 ToolCallingAdvisor：" : "挂 ToolCallingAdvisor：");
            long t0 = System.nanoTime();
            ChatResponse resp = client.prompt().user(OncallTools.QUESTION).call().chatResponse();
            System.out.println("\n=== Spring AI 结论 ===\n" + resp.getResult().getOutput().getText());
            var u = resp.getMetadata().getUsage();
            System.out.printf("%n工具调用 %d 次，累计输入 %d 输出 %d token，用时 %.1fs%n",
                    OncallTools.CALLS.get(), u.getPromptTokens(), u.getCompletionTokens(), (System.nanoTime() - t0) / 1e9);
        };
    }
}
