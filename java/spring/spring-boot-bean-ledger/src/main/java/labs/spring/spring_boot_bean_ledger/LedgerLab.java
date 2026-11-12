package labs.spring.spring_boot_bean_ledger;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 249 个 bean 是谁建的：把交易靶场的订单服务启动一次，记账。
 *   1. 自动配置的两道筛子：候选多少 → 第一道（只读 metadata）剩多少 → 第二道（条件注解）真装上多少
 *   2. 249 个 bean 按“谁登记的”分组；全景各站的对象出自哪张自动配置
 *   3. 启动时间花在哪：BufferingApplicationStartup 的步骤，按阶段和按 bean 的自身耗时排
 *   4. 发一次付款请求（POST /orders/9527/pay），看第一个请求要多久
 * 场景用 -Dlab.scenario=... 选，见 README；每个场景单独起一个 JVM，时间才可比。
 */
@SpringBootApplication
@EnableAsync
public class LedgerLab {
    static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("1024:pw".getBytes());

    public static void main(String[] args) throws Exception {
        String scenario = System.getProperty("lab.scenario", "base");
        boolean detail = Boolean.getBoolean("lab.detail");
        var app = new SpringApplication(LedgerLab.class);
        var startup = new BufferingApplicationStartup(20000);
        app.setApplicationStartup(startup);
        Map<String, Object> props = new HashMap<>();
        switch (scenario) {
            case "base" -> {}
            case "aop-jdk" -> props.put("spring.aop.proxy-target-class", "false");
            case "own-ds" -> props.put("lab.own-ds", "true");
            case "no-ds" -> props.put("spring.autoconfigure.exclude",
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
            case "lazy" -> props.put("spring.main.lazy-initialization", "true");
            case "eager-mvc" -> props.put("spring.mvc.servlet.load-on-startup", "1");
            case "jackson" -> props.put("spring.jackson.property-naming-strategy", "SNAKE_CASE");
            case "jackson-own" -> { props.put("spring.jackson.property-naming-strategy", "SNAKE_CASE"); props.put("lab.own-om", "true"); }
            default -> throw new IllegalArgumentException(scenario);
        }
        app.setDefaultProperties(props);
        System.out.println("== 场景 " + scenario + " " + props);

        long t0 = System.nanoTime();
        ConfigurableApplicationContext ctx;
        try {
            ctx = app.run(args);
        } catch (Exception e) {
            Throwable c = e; while (c.getCause() != null) c = c.getCause();
            System.out.println("启动失败：" + e.getClass().getSimpleName() + " → 根因 " + c.getClass().getSimpleName() + ": " + firstLine(c.getMessage()));
            System.out.println("（上面 *** APPLICATION FAILED TO START *** 那段是 FailureAnalyzer 给人看的版本）");
            return;
        }
        double runMs = (System.nanoTime() - t0) / 1e6;
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        var bf = ctx.getBeanFactory();
        int singletonsAtStart = bf.getSingletonCount();
        System.out.printf("启动：run() %.0f ms，JVM 启动到就绪 %d ms；bean 定义 %d 个，已建好的单例 %d 个%n",
                runMs, uptime, bf.getBeanDefinitionCount(), singletonsAtStart);

        filters(ctx, bf);
        ledger(bf, detail);
        stations(ctx);
        timeline(startup, detail);
        firstRequest(ctx, bf, singletonsAtStart);
        ctx.close();
    }

    // ---------- 1. 两道筛子 ----------
    static void filters(ConfigurableApplicationContext ctx, ConfigurableListableBeanFactory bf) {
        List<String> all = ImportCandidates.load(AutoConfiguration.class, LedgerLab.class.getClassLoader()).getCandidates();
        Set<String> cand = new LinkedHashSet<>(all);
        Set<String> afterFilter = new LinkedHashSet<>(ImportCounter.AFTER_FILTER);
        Set<String> applied = cand.stream().filter(bf::containsBeanDefinition).collect(Collectors.toCollection(LinkedHashSet::new));
        var report = ConditionEvaluationReport.get(bf);
        System.out.printf("自动配置：清单上 %d 个候选（%d 个 jar 的 AutoConfiguration.imports），排除 %d 个，"
                        + "第一道筛子后剩 %d 个，第二道之后真装上 %d 个%n",
                cand.size(), jarsOf(all), ImportCounter.EXCLUDED.size(), afterFilter.size(), applied.size());
        // 第二道刷掉的，看它们是被哪种条件刷掉的
        Map<String, Integer> why = new TreeMap<>();
        for (String c : afterFilter) {
            if (applied.contains(c)) continue;
            var co = report.getConditionAndOutcomesBySource().get(c);
            String k = "?";
            if (co != null) for (var x : co) if (!x.getOutcome().isMatch()) { k = x.getCondition().getClass().getSimpleName(); break; }
            why.merge(k, 1, Integer::sum);
        }
        System.out.println("  第二道刷掉的 " + why.values().stream().mapToInt(i -> i).sum() + " 个，按条件分：" + why);
        Map<String, Integer> why1 = new TreeMap<>();
        for (String c : cand) {
            if (afterFilter.contains(c) || ImportCounter.EXCLUDED.contains(c)) continue;
            var co = report.getConditionAndOutcomesBySource().get(c);
            String k = "?";
            if (co != null) for (var x : co) if (!x.getOutcome().isMatch()) { k = x.getCondition().getClass().getSimpleName(); break; }
            why1.merge(k, 1, Integer::sum);
        }
        System.out.println("  第一道刷掉的 " + why1.values().stream().mapToInt(i -> i).sum() + " 个，按条件分：" + why1);
        // 给 -Xlog:class+load 对照用：把三类名单写出来
        try {
            var dir = java.nio.file.Path.of(System.getProperty("lab.out", "target/ledger"));
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve("candidates.txt"), cand);
            java.nio.file.Files.write(dir.resolve("after-filter.txt"), afterFilter);
            java.nio.file.Files.write(dir.resolve("applied.txt"), applied);
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        // 几个有代表性的“没装上”的理由
        for (String c : List.of("org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration",
                "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")) {
            var co = report.getConditionAndOutcomesBySource().get(c);
            String stage = !afterFilter.contains(c) ? "第一道" : applied.contains(c) ? "装上" : "第二道";
            String msg = co == null ? "（无条件记录）" : co.stream().map(x -> (x.getOutcome().isMatch() ? "✓ " : "✗ ") + x.getOutcome().getMessage())
                    .collect(Collectors.joining(" | "));
            System.out.println("  " + simple(c) + " [" + stage + "] " + msg);
        }
    }

    /** 数有几个 jar 带了清单文件。只读资源，不用 Class.forName，免得把候选类都加载一遍、弄脏 -Xlog:class+load 的对照。 */
    static long jarsOf(List<String> names) {
        try {
            return Collections.list(LedgerLab.class.getClassLoader()
                    .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")).size();
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    // ---------- 2. 账本：249 个 bean 是谁登记的 ----------
    static void ledger(ConfigurableListableBeanFactory bf, boolean detail) {
        Set<String> autoConfigs = new HashSet<>(ImportCandidates.load(AutoConfiguration.class, LedgerLab.class.getClassLoader()).getCandidates());
        Map<String, List<String>> byCat = new LinkedHashMap<>();
        Map<String, Integer> byOwner = new HashMap<>();
        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition bd = bf.getBeanDefinition(name);
            String cls = strip(bd.getBeanClassName());
            String owner = bd.getFactoryBeanName() != null ? strip(bf.getBeanDefinition(bd.getFactoryBeanName()).getBeanClassName()) : cls;
            String top = owner == null ? "" : owner.split("\\$")[0];
            boolean bootAuto = top.contains(".boot.autoconfigure.") || autoConfigs.contains(top);
            String cat;
            if (top.startsWith("labs.")) cat = "A 我们写的类和它们的 @Bean";
            else if ("org.mybatis.spring.mapper.MapperFactoryBean".equals(cls)) cat = "A 我们写的 Mapper 接口（MyBatis 扫描后登记）";
            else if (name.contains("-") && cls != null && cls.endsWith("Properties")) cat = "B 自动配置：配置属性对象（XxxProperties）";
            else if (bootAuto && bd.getFactoryBeanName() == null) cat = "B 自动配置：配置类本身（含 @Import 的嵌套类）";
            else if (bootAuto) cat = "B 自动配置：配置类里的 @Bean";
            else if (bd.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE && name.startsWith("org.springframework.")
                    && bd.getFactoryBeanName() == null) cat = "D 容器内部处理器（internal*）";
            else cat = "C @Enable* 带进来的框架配置";
            if (detail) System.out.println("    [" + cat.charAt(0) + "] " + name + "  ← " + owner);
            byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(name);
            if (bootAuto) byOwner.merge(simple(top), 1, Integer::sum);
        }
        System.out.println("账本（按谁登记的分组）：");
        new TreeMap<>(byCat).forEach((k, v) -> System.out.printf("  %-28s %3d  例：%s%n", k, v.size(),
                v.stream().limit(detail ? 999 : 4).map(LedgerLab::shortName).collect(Collectors.joining(", "))));
        System.out.println("  出 bean 最多的自动配置（含它的嵌套类、@Bean 和它自己）：");
        byOwner.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(detail ? 999 : 12)
                .forEach(e -> System.out.printf("    %3d  %s%n", e.getValue(), e.getKey()));
    }

    // ---------- 全景各站的对象出自哪 ----------
    static void stations(ConfigurableApplicationContext ctx) {
        var bf = ctx.getBeanFactory();
        System.out.println("全景各站的对象：");
        for (String n : List.of("tomcatServletWebServerFactory", "springSecurityFilterChain", "dispatcherServlet", "mappingJackson2HttpMessageConverter",
                "jacksonObjectMapper", "objectMapper", "orderService", "org.springframework.aop.config.internalAutoProxyCreator", "transactionManager",
                "dataSource", "sqlSessionFactory", "sqlSessionTemplate", "orderMapper", "applicationTaskExecutor")) {
            if (!bf.containsBeanDefinition(n)) { System.out.printf("  %-30s （没有）%n", n); continue; }
            var bd = bf.getBeanDefinition(n);
            String owner = bd.getFactoryBeanName() != null ? strip(bf.getBeanDefinition(bd.getFactoryBeanName()).getBeanClassName()) : "（类本身）" + strip(bd.getBeanClassName());
            Object b = ctx.getBean(n);
            System.out.printf("  %-30s → %-45s 出自 %s%n", shortName(n), b.getClass().getName(), simple(owner));
        }
        var ds = ctx.getBean(DataSource.class);
        if (ds instanceof HikariDataSource h)
            System.out.println("  连接池：poolName=" + h.getPoolName() + " maximumPoolSize=" + h.getMaximumPoolSize());
        System.out.println("  spring.aop.proxy-target-class=" + ctx.getEnvironment().getProperty("spring.aop.proxy-target-class", "（没配）"));
    }

    // ---------- 3. 启动时间花在哪 ----------
    static void timeline(BufferingApplicationStartup startup, boolean detail) {
        List<StartupTimeline.TimelineEvent> ev = startup.getBufferedTimeline().getEvents();
        Map<Long, Long> childSum = new HashMap<>();
        for (var e : ev) if (e.getStartupStep().getParentId() != null)
            childSum.merge(e.getStartupStep().getParentId(), e.getDuration().toNanos(), Long::sum);
        System.out.println("启动步骤（BufferingApplicationStartup，ms；自身=去掉子步骤）：");
        for (var e : ev) {
            String n = e.getStartupStep().getName();
            if (e.getStartupStep().getParentId() == null || n.startsWith("spring.context.") || n.startsWith("spring.boot.webserver"))
                if (!n.equals("spring.beans.instantiate") && !n.equals("spring.beans.smart-initialize") && e.getDuration().toMillis() >= 5)
                    System.out.printf("  %-52s 总 %6.0f  自身 %6.0f%n", n + tags(e),
                            e.getDuration().toNanos() / 1e6, (e.getDuration().toNanos() - childSum.getOrDefault(e.getStartupStep().getId(), 0L)) / 1e6);
        }
        record B(String bean, double self, double total) {}
        List<B> beans = new ArrayList<>();
        double beanSelf = 0; int nInst = 0;
        for (var e : ev) if (e.getStartupStep().getName().equals("spring.beans.instantiate")) {
            double self = (e.getDuration().toNanos() - childSum.getOrDefault(e.getStartupStep().getId(), 0L)) / 1e6;
            beans.add(new B(tag(e, "beanName"), self, e.getDuration().toNanos() / 1e6));
            beanSelf += self; nInst++;
        }
        System.out.printf("  建 bean（spring.beans.instantiate）共 %d 次，自身耗时合计 %.0f ms；自身最慢的：%n", nInst, beanSelf);
        beans.stream().sorted(Comparator.comparingDouble(B::self).reversed()).limit(detail ? 30 : 10)
                .forEach(b -> System.out.printf("    %6.0f ms（含依赖 %6.0f）  %s%n", b.self, b.total, shortName(b.bean)));
    }

    // ---------- 4. 第一个请求 ----------
    static void firstRequest(ConfigurableApplicationContext ctx, ConfigurableListableBeanFactory bf, int singletonsAtStart) throws Exception {
        int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        for (int i = 1; i <= 2; i++) {
            long t = System.nanoTime();
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders/" + (i == 1 ? 9527 : 9529) + "/pay"))
                    .header("Content-Type", "application/json").header("Authorization", AUTH)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"channel\":\"alipay\",\"amountCents\":" + (i == 1 ? 79800 : 12900) + "}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.printf("第 %d 个请求：HTTP %d %s  用时 %.0f ms%n", i, r.statusCode(), r.body(), (System.nanoTime() - t) / 1e6);
        }
        System.out.println("已建好的单例：启动完 " + singletonsAtStart + " 个 → 两个请求之后 " + bf.getSingletonCount() + " 个");
    }

    // ---------- 小工具 ----------
    static String strip(String c) { return c == null ? null : c.replaceAll("\\$\\$SpringCGLIB\\$\\$\\d+", ""); }
    static String simple(String c) { return c == null ? "?" : c.replaceFirst("^.*\\.", "").replace("（类本身）", ""); }
    static String shortName(String n) { return n.startsWith("org.") || n.startsWith("com.") ? n.replaceFirst("^.*\\.", "") : n; }
    static String firstLine(String s) { return s == null ? "" : s.lines().findFirst().orElse(""); }
    static String tag(StartupTimeline.TimelineEvent e, String k) {
        for (var t : e.getStartupStep().getTags()) if (t.getKey().equals(k)) return t.getValue();
        return "?";
    }
    static String tags(StartupTimeline.TimelineEvent e) {
        var sb = new StringBuilder();
        for (var t : e.getStartupStep().getTags()) if (!t.getKey().equals("beanType") && !t.getKey().equals("mainApplicationClass"))
            sb.append(" ").append(t.getKey()).append("=").append(shortName(t.getValue()));
        return sb.length() > 40 ? sb.substring(0, 40) : sb.toString();
    }
}
