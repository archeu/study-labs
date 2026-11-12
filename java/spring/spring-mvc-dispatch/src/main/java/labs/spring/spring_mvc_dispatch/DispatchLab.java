package labs.spring.spring_mvc_dispatch;

import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import java.net.URI;
import java.net.http.*;
import java.util.*;

/**
 * 交易靶场订单服务，只看 Web 这几站（全景第 2～5、11 站）。
 * 场景：filters ok bad conflict npe filterboom anon public publicboom permit audit bench；不带参数全跑。
 */
@SpringBootApplication
public class DispatchLab {
    static final HttpClient HC = HttpClient.newHttpClient();
    static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("1024:pw".getBytes());
    static int port;

    public static void main(String[] args) throws Exception {
        List<String> want = args.length == 0
                ? List.of("filters", "ok", "bad", "conflict", "npe", "filterboom", "anon", "public", "publicboom", "permit", "audit", "bench")
                : List.of(args);
        ConfigurableApplicationContext ctx = null;
        for (String s : want) {
            if (s.equals("permit") || s.equals("audit") || s.equals("bench")) continue;
            if (ctx == null) ctx = start();
            run(ctx, s);
        }
        if (ctx != null) ctx.close();
        if (want.contains("bench")) {   // 单独起一个容器，第一次请求才是真的冷
            var c = start();
            bench();
            c.close();
        }
        if (want.contains("permit")) {
            var c = start("--lab.permit-error=true");
            System.out.println("\n### permit：同一个 /public/orders/0，这次安全链放行了 /error（--lab.permit-error=true）");
            send("GET", "/public/orders/0", null, null);
            c.close();
        }
        if (want.contains("audit")) {
            var c = start("--lab.audit-in-security=true");
            System.out.println("\n### audit：AuditFilter 既是 @Component，又被 addFilterAfter 加进安全链");
            send("POST", "/orders/9527/pay", "{\"channel\":\"alipay\",\"amountCents\":79800}", AUTH);
            c.close();
        }
    }

    static ConfigurableApplicationContext start(String... args) {
        var ctx = SpringApplication.run(DispatchLab.class, args);
        port = ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
        return ctx;
    }

    static void run(ConfigurableApplicationContext ctx, String s) throws Exception {
        String pay = "{\"channel\":\"alipay\",\"amountCents\":79800}";
        switch (s) {
            case "filters" -> dumpFilters(ctx);
            case "ok" -> { title(s, "用户 1024 给订单 9527 付 798.00"); send("POST", "/orders/9527/pay", pay, AUTH); }
            case "bad" -> { title(s, "请求体 amountCents=-1，@Positive 校验不过");
                send("POST", "/orders/9527/pay", "{\"channel\":\"alipay\",\"amountCents\":-1}", AUTH); }
            case "conflict" -> { title(s, "订单 9529 应付 129 元，只付 1 元：Controller 抛 IllegalStateException，@ExceptionHandler 接住");
                send("POST", "/orders/9529/pay", "{\"channel\":\"alipay\",\"amountCents\":100}", AUTH); }
            case "npe" -> { title(s, "订单 9530：Controller 抛 NullPointerException，没人接");
                send("POST", "/orders/9530/pay", "{\"channel\":\"alipay\",\"amountCents\":100}", AUTH); }
            case "filterboom" -> { title(s, "PlainFilter 里抛异常（请求头 X-Boom: filter）");
                send("POST", "/orders/9527/pay", pay, AUTH, "X-Boom", "filter"); }
            case "anon" -> { title(s, "不带账号付款"); send("POST", "/orders/9527/pay", pay, null); }
            case "public" -> { title(s, "不带账号查公开接口 /public/orders/9527"); send("GET", "/public/orders/9527", null, null); }
            case "publicboom" -> { title(s, "不带账号查 /public/orders/0：公开接口自己抛了异常");
                send("GET", "/public/orders/0", null, null); }
            case "bench" -> bench();
            default -> System.out.println("未知场景 " + s);
        }
    }

    static void title(String s, String d) { System.out.println("\n### " + s + "：" + d); }

    static HttpResponse<String> send(String method, String path, String body, String auth, String... hdr) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        for (int i = 0; i + 1 < hdr.length; i += 2) b.header(hdr[i], hdr[i + 1]);
        Trace.reset();
        var r = HC.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(30); // 等服务端线程把最后几行记完
        synchronized (Trace.EV) { Trace.EV.forEach(l -> System.out.println("  " + l)); }
        String rb = r.body().length() > 160 ? r.body().substring(0, 160) + "…" : r.body();
        System.out.println("  → HTTP " + r.statusCode() + " " + rb);
        return r;
    }

    /** Tomcat 里实际登记的过滤器（顺序就是执行顺序）和每个能接哪些分派；再展开 Security 的那一个。 */
    static void dumpFilters(ConfigurableApplicationContext ctx) {
        System.out.println("\n### filters：Tomcat 上登记的过滤器，按执行顺序");
        var tomcat = ((TomcatWebServer) ((ServletWebServerApplicationContext) ctx).getWebServer()).getTomcat();
        Context c = (Context) tomcat.getHost().findChildren()[0];
        int i = 1;
        for (FilterMap m : c.findFilterMaps()) {
            String cls = c.findFilterDef(m.getFilterName()).getFilterClass();
            System.out.printf("  %d. %-28s %-62s %s%n", i++, m.getFilterName(), cls.substring(cls.lastIndexOf('.') + 1),
                    String.join(",", m.getDispatcherNames()));
        }
        var fcp = ctx.getBean("springSecurityFilterChain", FilterChainProxy.class);
        fcp.getFilterChains().forEach(ch -> {
            System.out.println("  springSecurityFilterChain 里的一条链，" + ch.getFilters().size() + " 个过滤器：");
            ch.getFilters().forEach(f -> System.out.println("     - " + f.getClass().getSimpleName()));
        });
    }

    /** 冷的第一次请求，和热身后的 300 次，按计时点分段（服务端纳秒，取中位数）。 */
    static void bench() throws Exception {
        System.out.println("\n### bench：各段耗时（F0 PlainFilter 进 → P preHandle → B JSON 读完 → C 进 Controller → W 要写 JSON → E afterCompletion → F1 PlainFilter 出）");
        String[] seg = {"F0", "P", "B", "C", "W", "E", "F1"};
        String[] name = {"过滤器链+安全+找方法", "读 JSON", "校验+其余参数", "Controller", "写 JSON+提交", "回到过滤器"};
        String body = "{\"channel\":\"alipay\",\"amountCents\":79800}";
        Trace.on = false;
        int n = 300, warm = 200;
        double[][] d = new double[n][seg.length];
        double[] client = new double[n];
        for (int k = -warm; k < n; k++) {
            var rq = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/9527/pay"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json")
                    .header("Authorization", AUTH).build();
            Trace.reset();
            long t0 = System.nanoTime();
            HC.send(rq, HttpResponse.BodyHandlers.ofString());
            long t1 = System.nanoTime();
            Thread.sleep(2);
            if (k == -warm) report("冷的第一次", seg, name, Trace.MARK, (t1 - t0) / 1e6);
            if (k >= 0) {
                client[k] = (t1 - t0) / 1e6;
                for (int j = 1; j < seg.length; j++) d[k][j] = (Trace.MARK.get(seg[j]) - Trace.MARK.get(seg[j - 1])) / 1e6;
                d[k][0] = (Trace.MARK.get("F1") - Trace.MARK.get("F0")) / 1e6;
            }
        }
        StringBuilder sb = new StringBuilder("  热身 " + warm + " 次后 " + n + " 次的中位数：");
        for (int j = 1; j < seg.length; j++) sb.append(String.format("%n     %-16s %.3f ms", name[j - 1], med(d, j)));
        sb.append(String.format("%n     服务端合计 %.3f ms，客户端看到 %.3f ms", med(d, 0), median(client)));
        System.out.println(sb);
        Trace.on = true;
    }

    static void report(String tag, String[] seg, String[] name, Map<String, Long> m, double clientMs) {
        StringBuilder sb = new StringBuilder("  " + tag + "：");
        for (int j = 1; j < seg.length; j++)
            sb.append(String.format("%n     %-16s %.2f ms", name[j - 1], (m.get(seg[j]) - m.get(seg[j - 1])) / 1e6));
        sb.append(String.format("%n     服务端合计 %.2f ms，客户端看到 %.2f ms", (m.get("F1") - m.get("F0")) / 1e6, clientMs));
        System.out.println(sb);
    }

    static double med(double[][] d, int j) { double[] c = new double[d.length]; for (int i = 0; i < d.length; i++) c[i] = d[i][j]; return median(c); }
    static double median(double[] a) { double[] c = a.clone(); Arrays.sort(c); return c[c.length / 2]; }
}
