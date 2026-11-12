package labs.java.pano_j05_request_through_spring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.web.FilterChainProxy;

/**
 * 全景 J05：用户 1024 给订单 9527 付款，POST /orders/9527/pay。
 * 启动应用 → 发几次请求 → 打印每一站留下的痕迹、SQL 发出那一刻的调用栈、几组对照。
 * 运行：mvn -q -pl java/pano-j05-request-through-spring compile exec:java -Dexec.mainClass=labs.java.pano_j05_request_through_spring.RequestThroughSpring
 */
@SpringBootApplication
@EnableAsync
public class RequestThroughSpring {
    static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("1024:pw".getBytes());
    // 固定用 HTTP/1.1：JDK HttpClient 默认先发 h2c 升级请求，Tomcat 没开 h2c，会多绕一圈
    static HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static int port;

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        ConfigurableApplicationContext ctx = SpringApplication.run(RequestThroughSpring.class, args);
        double startMs = (System.nanoTime() - t0) / 1e6;
        port = ((WebServerApplicationContext) ctx).getWebServer().getPort();

        section("① 启动：请求来之前，Spring Boot 装好了什么");
        System.out.printf("启动用时 %.0f ms，容器里 %d 个 bean%n", startMs, ctx.getBeanDefinitionCount());
        for (String n : List.of("orderService", "orderMapper", "dispatcherServlet", "dataSource", "sqlSessionFactory", "transactionManager"))
            System.out.printf("  %-18s → %s%n", n, ctx.getBean(n).getClass().getName());
        var chains = ctx.getBean(FilterChainProxy.class).getFilterChains();
        var names = chains.get(0).getFilters().stream().map(f -> f.getClass().getSimpleName()).toList();
        System.out.printf("Security 过滤器链：%d 条，第一条里 %d 个过滤器：%s%n", chains.size(), names.size(), names);

        section("② ～ ⑪ 第一次请求（冷）：用户 1024 给 9527 付 798.00 元");
        var r = post("/orders/9527/pay", "{\"channel\":\"alipay\",\"amountCents\":79800}", true);
        System.out.println("HTTP " + r.statusCode() + " " + r.body() + String.format("  客户端看到的总用时 %.1f ms", last));
        Thread.sleep(300);   // 等异步监听器跑完
        Trace.EVENTS.forEach(System.out::println);

        section("⑨ SQL 发出去那一刻的调用栈（从 Tomcat 线程的底往上）");
        @SuppressWarnings("unchecked") List<String> st = (List<String>) Trace.FACTS.get("stack");
        System.out.println("一共 " + st.size() + " 层，挑出关键的几层（编号=离栈顶的层数）：");
        String[] keys = {"SqlTracePlugin.intercept", "Plugin.invoke", "SimpleExecutor.doUpdate", "CachingExecutor.update",
                "DefaultSqlSession.update", "SqlSessionTemplate$SqlSessionInterceptor.invoke", "MapperMethod.execute",
                "MapperProxy.invoke", "OrderService.pay", "TransactionInterceptor.invoke", "CglibAopProxy$DynamicAdvisedInterceptor.intercept",
                "OrderController.pay", "InvocableHandlerMethod.doInvoke", "DispatcherServlet.doDispatch", "FilterChainProxy.doFilter",
                "AuthorizationFilter.doFilter", "BasicAuthenticationFilter.doFilterInternal", "TimingFilter.doFilterInternal",
                "StandardWrapperValve.invoke", "Http11Processor.service", "NioEndpoint$SocketProcessor.doRun", "ThreadPoolExecutor.runWorker"};
        List<String> pruned = new ArrayList<>();
        for (int i = st.size() - 1; i >= 0; i--) {
            String f = st.get(i);
            String shortName = f.substring(f.lastIndexOf('.', f.lastIndexOf('.') - 1) + 1);
            for (String k : keys) if (shortName.equals(k) || f.endsWith("." + k)) { pruned.add(String.format("  %3d  %s", i, f)); break; }
        }
        pruned.forEach(System.out::println);
        long ourPkg = st.stream().filter(f -> f.startsWith("labs.")).count();
        Map<String, Long> by = new TreeMap<>();
        for (String f : st) {
            String g = f.startsWith("org.apache.catalina") || f.startsWith("org.apache.coyote") || f.startsWith("org.apache.tomcat") ? "Tomcat"
                    : f.startsWith("org.springframework.security") ? "Spring Security"
                    : f.startsWith("org.springframework.web") ? "Spring MVC / web"
                    : f.startsWith("org.springframework.aop") || f.startsWith("org.springframework.transaction") ? "AOP + 事务"
                    : f.startsWith("org.apache.ibatis") || f.startsWith("org.mybatis") ? "MyBatis"
                    : f.startsWith("labs.") ? "我们写的代码"
                    : f.startsWith("jdk.") || f.startsWith("java.") || f.startsWith("jdk.internal") ? "JDK（反射、代理、线程）"
                    : "其他（" + f.substring(0, f.indexOf('.', f.indexOf('.') + 1)) + "）";
            by.merge(g, 1L, Long::sum);
        }
        System.out.println("按来源分组：" + by + "；其中我们自己写的方法 " + ourPkg + " 层");
        System.out.println("Service 里的连接#" + Trace.FACTS.get("svc.con") + "，发 SQL 时的连接#" + Trace.FACTS.get("sql.con")
                + " → " + (Objects.equals(Trace.FACTS.get("svc.con"), Trace.FACTS.get("sql.con")) ? "同一条" : "不是同一条"));
        System.out.println("Controller 线程=" + Trace.FACTS.get("controller.thread") + "，监听器线程=" + Trace.FACTS.get("listener.thread"));

        section("时间：热身后再付 300 次（每次付完把状态改回 CREATED）");
        Trace.on = false;
        var mapper = ctx.getBean(OrderMapper.class);
        List<Double> lat = new ArrayList<>();
        Map<Integer, List<Double>> segs = new TreeMap<>();
        long sqlNanosBefore = (Long) Trace.FACTS.getOrDefault("sql.nanos", 0L);
        for (int i = 0; i < 600; i++) {
            mapper.changeStatus(9527, "PAID", "CREATED");
            post("/orders/9527/pay", "{\"channel\":\"alipay\",\"amountCents\":79800}", true);
            if (i >= 300) {
                lat.add(last);
                for (int s = 0; s < SEG.length; s++)
                    segs.computeIfAbsent(s, x -> new ArrayList<>()).add((Trace.MARK.get(SEG[s][1]) - Trace.MARK.get(SEG[s][0])) / 1e6);
            }
        }
        Collections.sort(lat);
        long sqlNanos = (Long) Trace.FACTS.get("sql.nanos") - sqlNanosBefore;
        System.out.printf("后 300 次：p50 %.2f ms，p90 %.2f ms，p99 %.2f ms；其中 UPDATE 在驱动里平均 %.3f ms（600 次平均）%n",
                lat.get(149), lat.get(269), lat.get(296), sqlNanos / 600 / 1e6);
        System.out.println("服务端分段（300 次的中位数）：");
        for (int s = 0; s < SEG.length; s++) {
            var l = segs.get(s); Collections.sort(l);
            System.out.printf("  %-42s %7.3f ms%n", SEG[s][2], l.get(l.size() / 2));
        }
        Trace.on = true;
        String stored = ctx.getBean(org.springframework.security.provisioning.InMemoryUserDetailsManager.class).loadUserByUsername("1024").getPassword();
        System.out.println("此时内存里用户 1024 的密码存成：" + stored.substring(0, Math.min(14, stored.length())) + "…");
        var bc = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        String h = bc.encode("pw"); for (int i = 0; i < 5; i++) bc.matches("pw", h);
        long tb = System.nanoTime(); for (int i = 0; i < 10; i++) bc.matches("pw", h);
        System.out.printf("单独量一次 BCrypt（强度 10）校验：%.1f ms%n", (System.nanoTime() - tb) / 10 / 1e6);

        section("对照 1：不带账号 → 停在第 3 站");
        Trace.EVENTS.clear();
        r = post("/orders/9527/pay", "{\"channel\":\"alipay\",\"amountCents\":79800}", false);
        System.out.println("HTTP " + r.statusCode() + "，WWW-Authenticate=" + r.headers().firstValue("WWW-Authenticate").orElse("-"));
        Trace.EVENTS.forEach(System.out::println);

        section("对照 2：请求体不合法（amountCents=-1）→ 停在第 5 站");
        Trace.EVENTS.clear();
        r = post("/orders/9527/pay", "{\"channel\":\"alipay\",\"amountCents\":-1}", true);
        System.out.println("HTTP " + r.statusCode());
        Trace.EVENTS.forEach(System.out::println);

        section("对照 3：订单 9529 金额不符 → UPDATE 已执行，抛异常后回滚");
        Trace.EVENTS.clear();
        r = post("/orders/9529/pay", "{\"channel\":\"alipay\",\"amountCents\":100}", true);
        Thread.sleep(300);
        System.out.println("HTTP " + r.statusCode() + " " + r.body() + "；之后库里 9529 的状态=" + mapper.findById(9529).status());
        Trace.EVENTS.forEach(System.out::println);

        section("对照 4：同样金额不符，但走 this.pay()（自调用，绕过代理）");
        Trace.EVENTS.clear();
        r = post("/orders/9529/pay-self", "{\"channel\":\"alipay\",\"amountCents\":100}", true);
        Thread.sleep(300);
        System.out.println("HTTP " + r.statusCode() + " " + r.body() + "；之后库里 9529 的状态=" + mapper.findById(9529).status());
        Trace.EVENTS.forEach(System.out::println);

        ctx.close();
    }

    static final String[][] SEG = {
        {"F0", "P", "③④ 过滤器链（含认证）+ 找 Controller"},
        {"P", "C", "⑤ 读 JSON、转成 PayRequest、校验"},
        {"C", "S", "⑥⑦ 代理 + 开事务（借连接、关自动提交）"},
        {"S", "Q0", "⑧ 业务代码 + 一次 SELECT + MyBatis 准备"},
        {"Q0", "Q1", "⑨ UPDATE 在驱动里执行"},
        {"Q1", "Z", "⑨ 之后到业务方法返回"},
        {"Z", "R", "⑩ 提交事务、还连接"},
        {"R", "E", "⑪ 返回值写成 JSON + afterCompletion"},
        {"F0", "F1", "合计：TimingFilter 量到的整段"}};
    static double last;
    static HttpResponse<String> post(String path, String body, boolean auth) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth) b.header("Authorization", AUTH);
        long t = System.nanoTime();
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        last = (System.nanoTime() - t) / 1e6;
        return r;
    }
    static void section(String s) { System.out.println("\n=== " + s + " ==="); }
}
