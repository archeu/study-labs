package labs.spring.spring_security_token;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.FilterChainProxy;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * 交易靶场订单服务，全景第 3 站：用户 1024 付订单 9527，凭什么证明“我是 1024”。
 * 场景：chain basic session nosave jwt idor scope tamper expired logout fixation bench bcrypt；不带参数全跑。
 */
@SpringBootApplication
public class TokenLab {
    static final HttpClient HC = HttpClient.newHttpClient();
    static final String BASIC = "Basic " + Base64.getEncoder().encodeToString("1024:pw".getBytes());
    static final String LOGIN_1024 = "{\"username\":\"1024\",\"password\":\"pw\"}";
    static final String LOGIN_1025 = "{\"username\":\"1025\",\"password\":\"pw\"}";
    static int port;
    static ConfigurableApplicationContext ctx;

    public static void main(String[] args) throws Exception {
        List<String> want = args.length == 0
                ? List.of("chain", "basic", "session", "nosave", "jwt", "idor", "scope", "tamper", "expired", "logout", "fixation", "bench", "bcrypt")
                : List.of(args);
        ctx = SpringApplication.run(TokenLab.class);
        port = ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
        for (String s : want) run(s);
        ctx.close();
    }

    static void run(String s) throws Exception {
        switch (s) {
            case "chain" -> chain();
            case "basic" -> { title(s, "每个请求都带 Basic 头（用户名:密码），和全景实测时一样");
                send("POST", "/orders/9527/pay", "Authorization", BASIC); }
            case "session" -> { title(s, "先登录一次拿 JSESSIONID，之后只带 cookie");
                var r = send("POST", "/login/session", null, null, LOGIN_1024);
                send("POST", "/orders/9527/pay", "Cookie", cookie(r)); }
            case "nosave" -> { title(s, "登录接口验了密码，却没调 SecurityContextRepository.saveContext");
                var r = send("POST", "/login/session?save=false", null, null, LOGIN_1024);
                System.out.println("  Set-Cookie：" + r.headers().firstValue("Set-Cookie").orElse("（没有）"));
                send("POST", "/orders/9527/pay", "Cookie", cookie(r)); }
            case "jwt" -> { title(s, "登录一次换 JWT，之后带 Authorization: Bearer");
                String t = token(LOGIN_1024);
                String[] p = t.split("\\.");
                System.out.println("  令牌 " + t.length() + " 字节，三段：");
                System.out.println("    header  " + dec(p[0]));
                System.out.println("    payload " + dec(p[1]));
                System.out.println("    签名    " + p[2] + "（HMAC-SHA256，32 字节）");
                send("POST", "/orders/9527/pay", "Authorization", "Bearer " + t); }
            case "idor" -> { title(s, "用户 1025 拿自己合法的令牌，去付 1024 的订单 9527");
                String t = token(LOGIN_1025);
                send("POST", "/orders/9527/pay", "Authorization", "Bearer " + t);
                System.out.println("  对照：1025 付自己的 9528");
                send("POST", "/orders/9528/pay", "Authorization", "Bearer " + t); }
            case "scope" -> { title(s, "@PreAuthorize(\"hasAuthority('order:pay')\")：同一个人，换种凭证");
                var r = send("POST", "/login/session", null, null, LOGIN_1024);
                System.out.println("  带 JSESSIONID：");
                send("POST", "/orders/9527/cancel", "Cookie", cookie(r));
                System.out.println("  带 JWT（payload 里 scope=order:pay）：");
                send("POST", "/orders/9527/cancel", "Authorization", "Bearer " + token(LOGIN_1024)); }
            case "tamper" -> { title(s, "1025 把自己令牌 payload 里的 sub 改成 1024，签名不动");
                String[] p = token(LOGIN_1025).split("\\.");
                String fake = enc(dec(p[1]).replace("\"sub\":\"1025\"", "\"sub\":\"1024\""));
                send("POST", "/orders/9527/pay", "Authorization", "Bearer " + p[0] + "." + fake + "." + p[2]); }
            case "expired" -> { title(s, "令牌已过期 30 秒、90 秒");
                JwtEncoder e = ctx.getBean(JwtEncoder.class);
                System.out.println("  过期 30 秒：");
                send("POST", "/orders/9527/pay", "Authorization", "Bearer " + LoginController.issue(e, "1024", Instant.now().minusSeconds(30)));
                System.out.println("  过期 90 秒：");
                send("POST", "/orders/9527/pay", "Authorization", "Bearer " + LoginController.issue(e, "1024", Instant.now().minusSeconds(90))); }
            case "logout" -> { title(s, "登出以后，旧凭证还能不能用");
                var r = send("POST", "/login/session", null, null, LOGIN_1024);
                String ck = cookie(r);
                send("POST", "/logout/session", "Cookie", ck);
                System.out.println("  会话：登出后再用旧 JSESSIONID 付款");
                send("POST", "/orders/9527/pay", "Cookie", ck);
                String t = token(LOGIN_1024);
                System.out.println("  JWT：客户端“登出”只是把令牌扔掉；被人抄走的那一份再拿来付款");
                send("POST", "/orders/9527/pay", "Authorization", "Bearer " + t); }
            case "fixation" -> { title(s, "登录前就有会话号（攻击者可以塞给你），登录后会不会换");
                var h = send("GET", "/public/hello", null, null);
                String ck = cookie(h);
                send("POST", "/login/session", "Cookie", ck, LOGIN_1024);
                System.out.println("  加 rotate=true（登录时 request.changeSessionId()）：");
                h = send("GET", "/public/hello", null, null);
                send("POST", "/login/session?rotate=true", "Cookie", cookie(h), LOGIN_1024); }
            case "bench" -> bench();
            case "bcrypt" -> bcrypt();
            default -> System.out.println("未知场景 " + s);
        }
    }

    static void title(String s, String d) { System.out.println("\n### " + s + "：" + d); }

    static String token(String login) throws Exception {
        var r = raw("POST", "/login/token", null, null, login);
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(r.body());
        if (!m.find()) throw new IllegalStateException(r.body());
        return m.group(1);
    }

    static String cookie(HttpResponse<String> r) {
        return r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID="))
                .map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElse(null);
    }

    static String dec(String b64) { return new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8); }
    static String enc(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    static HttpResponse<String> raw(String method, String path, String hName, String hVal, String... body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body[0]))
                .header("Content-Type", "application/json");
        if (hName != null && hVal != null) b.header(hName, hVal);
        Trace.reset();
        return HC.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(String method, String path, String hName, String hVal, String... body) throws Exception {
        var r = raw(method, path, hName, hVal, body);
        Thread.sleep(20);
        synchronized (Trace.EV) { Trace.EV.forEach(l -> System.out.println("    " + l)); }
        String shown = hVal == null ? "" : "  [" + hName + ": " + (hVal.length() > 40 ? hVal.substring(0, 40) + "…" : hVal) + "]";
        String rb = r.body().length() > 150 ? r.body().substring(0, 150) + "…" : r.body();
        System.out.println("  " + method + " " + path + shown + " → HTTP " + r.statusCode() + " " + rb);
        r.headers().firstValue("WWW-Authenticate").ifPresent(w -> System.out.println("      WWW-Authenticate: " + w));
        return r;
    }

    static void chain() {
        title("chain", "安全链里的过滤器（同时开了 httpBasic 和 oauth2ResourceServer.jwt）");
        var fcp = ctx.getBean("springSecurityFilterChain", FilterChainProxy.class);
        fcp.getFilterChains().forEach(ch -> {
            System.out.println("  " + ch.getFilters().size() + " 个过滤器：");
            ch.getFilters().forEach(f -> System.out.println("     - " + f.getClass().getSimpleName()));
        });
    }

    /** 三种凭证各发 N 次付款，服务端“进门 → 进 Controller”的中位数（认证 + 授权都在这段里）。 */
    static void bench() throws Exception {
        title("bench", "三种凭证，服务端从进门到 Controller 的耗时（热身后中位数）");
        Trace.on = false;
        String ck = cookie(raw("POST", "/login/session", null, null, LOGIN_1024));
        String t = "Bearer " + token(LOGIN_1024);
        Object[][] modes = {{"Basic（每次 BCrypt）", "Authorization", BASIC, 50, 100},
                {"JSESSIONID（查会话）", "Cookie", ck, 300, 300},
                {"JWT HS256（验签名）", "Authorization", t, 300, 300}};
        for (Object[] m : modes) {
            int warm = (int) m[3], n = (int) m[4];
            double[] auth = new double[n], all = new double[n], cli = new double[n];
            for (int k = -warm; k < n; k++) {
                long t0 = System.nanoTime();
                var r = raw("POST", "/orders/9527/pay", (String) m[1], (String) m[2]);
                long t1 = System.nanoTime();
                if (r.statusCode() != 200) throw new IllegalStateException(m[0] + " → " + r.statusCode());
                Thread.sleep(1);
                if (k >= 0) {
                    auth[k] = (Trace.MARK.get("C") - Trace.MARK.get("F0")) / 1e6;
                    all[k] = (Trace.MARK.get("F1") - Trace.MARK.get("F0")) / 1e6;
                    cli[k] = (t1 - t0) / 1e6;
                }
            }
            System.out.printf("  %-18s 进门→Controller %8.3f ms   服务端合计 %8.3f ms   客户端 %8.3f ms   （%d 次）%n",
                    m[0], median(auth), median(all), median(cli), n);
        }
        Trace.on = true;
    }

    /** BCrypt 的 cost（strength）每加 1，算一次的时间翻一倍。 */
    static void bcrypt() {
        title("bcrypt", "BCryptPasswordEncoder.matches 一次要多久（各 5 次取中位数）");
        for (int cost : new int[]{4, 8, 10, 11, 12}) {
            var e = new BCryptPasswordEncoder(cost);
            String h = e.encode("pw");
            double[] d = new double[5];
            for (int i = 0; i < 5; i++) { long t0 = System.nanoTime(); e.matches("pw", h); d[i] = (System.nanoTime() - t0) / 1e6; }
            System.out.printf("  cost %2d  %s…  %8.2f ms%n", cost, h.substring(0, 7), median(d));
        }
    }

    static double median(double[] a) { double[] c = a.clone(); Arrays.sort(c); return c[c.length / 2]; }
}
