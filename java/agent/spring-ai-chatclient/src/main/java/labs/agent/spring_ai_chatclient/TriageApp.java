package labs.agent.spring_ai_chatclient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Set;

/**
 * 站 03 的分诊服务，Java 版。业务代码只依赖 ChatClient，用哪家模型由 starter + 配置决定。
 *
 * <pre>
 *   mvn -q spring-boot:run                                  # Anthropic 模块 → 本机 /v1/messages
 *   mvn -q spring-boot:run -Dspring-boot.run.profiles=openai  # OpenAI 模块 → 本机 Qwen3（只改配置）
 * </pre>
 */
@SpringBootApplication
public class TriageApp {

    /** 结构化结果：ChatClient 的 entity() 会按它生成格式说明、再把回答解析成它。 */
    public record Triage(String type, String level, String cause, String firstCheck) {
        static final Set<String> TYPES = Set.of("GC", "数据库", "支付", "前端");
        static final Set<String> LEVELS = Set.of("P1", "P2", "P3");

        boolean valid() {
            return TYPES.contains(type) && LEVELS.contains(level) && cause != null && firstCheck != null;
        }
    }

    static final String SYSTEM = """
            你是值班告警分诊助手。根据 <alert> 里的告警分诊。
            type 只能是 GC、数据库、支付、前端 之一；level 只能是 P1、P2、P3 之一；
            cause 一句话写可能原因；firstCheck 一句话写先查什么。""";

    public static void main(String[] args) {
        SpringApplication.run(TriageApp.class, args);
    }

    @Bean
    CommandLineRunner demo(ChatClient.Builder builder) {
        return args -> {
            ChatClient chat = builder.defaultSystem(SYSTEM).build();          // 不变的部分放进默认 system
            String alert = "<alert>order-04 Pause Full 1873ms，接口 P99 升到 2.1s</alert>";

            // 1. 普通调用：拿文字，顺便看用量
            long t0 = System.nanoTime();
            ChatResponse resp = chat.prompt().user(alert + " 用一句话回答是什么类型的问题。").call().chatResponse();
            System.out.printf("1) 文字：%s%n   用量：%s，%d ms%n", resp.getResult().getOutput().getText().strip(),
                    resp.getMetadata().getUsage(), (System.nanoTime() - t0) / 1_000_000);

            // 2. 结构化：entity() 自动加格式说明并解析；校验失败带着原因再要一次（最多 3 次）
            for (int attempt = 1; attempt <= 3; attempt++) {
                t0 = System.nanoTime();
                try {
                    Triage t = chat.prompt().user(alert).call().entity(Triage.class);
                    System.out.printf("2) 第 %d 次 entity()：%s，%s，%d ms%n", attempt, t,
                            t != null && t.valid() ? "校验通过" : "取值不合规", (System.nanoTime() - t0) / 1_000_000);
                    if (t != null && t.valid()) break;
                } catch (RuntimeException e) {
                    System.out.printf("2) 第 %d 次 entity() 解析失败：%s%n", attempt, e.getClass().getSimpleName());
                }
            }

            // 3. 流式：Flux<String>，打印每段到达的毫秒数
            long s0 = System.nanoTime();
            StringBuilder sb = new StringBuilder();
            int[] n = {0};
            long[] first = {-1};
            chat.prompt().user(alert + " 用三句话说明排查步骤。").stream().content()
                    .doOnNext(piece -> {
                        if (first[0] < 0) first[0] = (System.nanoTime() - s0) / 1_000_000;
                        n[0]++;
                        sb.append(piece);
                    })
                    .blockLast();
            System.out.printf("3) 流式：%d 段，首段 %d ms，共 %d ms：%s%n", n[0], first[0],
                    (System.nanoTime() - s0) / 1_000_000, sb.toString().strip().replace("\n", " "));
        };
    }
}
