package labs.spring.spring_boot4_upgrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.SpringVersion;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动交易靶场的付款接口，再用 JDK HttpClient 发 6 个请求，打印状态码和响应体。
 * 用不同 profile 编译运行，对照 Boot 3.5 和 Boot 4 的输出：
 *   mvn -q -Pboot3 compile exec:java ...（命令见 README）
 * 加 -Dspring.jackson.use-jackson2-defaults=true 可以看 Boot 4 的“Jackson 2 兼容默认值”。
 */
@SpringBootApplication
public class UpgradeLab {

    static final Map<String, String> CASES = new LinkedHashMap<>();
    static {
        CASES.put("1 正常付款", "{\"channel\":\"ALIPAY\",\"amountCents\":79800}");
        CASES.put("2 金额是 null", "{\"channel\":\"ALIPAY\",\"amountCents\":null}");
        CASES.put("3 没传金额", "{\"channel\":\"ALIPAY\"}");
        CASES.put("4 多一个字段", "{\"channel\":\"ALIPAY\",\"amountCents\":79800,\"coupon\":\"NEW10\"}");
        CASES.put("5 尾巴多一段", "{\"channel\":\"ALIPAY\",\"amountCents\":79800}{\"x\":1}");
        CASES.put("6 渠道写中文名", "{\"channel\":\"支付宝\",\"amountCents\":79800}");
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        ConfigurableApplicationContext ctx = SpringApplication.run(UpgradeLab.class, args);
        double startMs = (System.nanoTime() - t0) / 1e6;
        Environment env = ctx.getEnvironment();
        int port = Integer.parseInt(env.getProperty("local.server.port"));
        System.out.printf("== Spring Boot %s / Spring Framework %s / Jackson %s / Servlet API %s%n",
                SpringBootVersion.getVersion(), SpringVersion.getVersion(), jacksonVersion(),
                jakarta.servlet.Servlet.class.getPackage().getImplementationVersion());
        System.out.printf("== 启动 %.0f ms，bean %d 个，use-jackson2-defaults=%s%n", startMs,
                ctx.getBeanDefinitionCount(), env.getProperty("spring.jackson.use-jackson2-defaults", "（没设）"));
        System.out.println("== 容器里的 JSON mapper：" + mapperBeans(ctx));

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString("1024:pw".getBytes());
        for (var e : CASES.entrySet()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders/9527/pay"))
                    .header("Content-Type", "application/json").header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(e.getValue())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("-- " + e.getKey() + "  请求 " + e.getValue());
            System.out.println("   " + r.statusCode() + " " + r.body());
        }
        ctx.close();
    }

    static String jacksonVersion() {
        for (String cn : new String[]{"tools.jackson.databind.ObjectMapper", "com.fasterxml.jackson.databind.ObjectMapper"}) {
            try {
                Class<?> c = Class.forName(cn);
                Object v = c.getMethod("version").invoke(c.getDeclaredConstructor().newInstance());
                return v + "（" + cn.substring(0, cn.indexOf(".databind")) + "）";
            } catch (Exception ignored) { }
        }
        return "?";
    }

    static String mapperBeans(ConfigurableApplicationContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (String n : ctx.getBeanDefinitionNames()) {
            if (n.toLowerCase().contains("mapper") && !n.contains("orderMapper") && !n.toLowerCase().contains("mybatis")) {
                Object b = ctx.getBean(n);
                sb.append(n).append("=").append(b.getClass().getName()).append("  ");
            }
        }
        return sb.toString();
    }
}
