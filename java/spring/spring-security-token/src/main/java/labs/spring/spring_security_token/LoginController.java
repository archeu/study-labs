package labs.spring.spring_security_token;

import jakarta.servlet.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/** 登录：密码只在这里验一次（一次 BCrypt），换出一张“通行证”。 */
@RestController
public class LoginController {
    private final AuthenticationManager am;
    private final SecurityContextRepository repo;
    private final JwtEncoder enc;

    public LoginController(AuthenticationManager am, SecurityContextRepository repo, JwtEncoder enc) {
        this.am = am; this.repo = repo; this.enc = enc;
    }

    public record Cred(String username, String password) {}

    /** 方式一：会话。认证结果放进 HttpSession，浏览器拿 JSESSIONID。save=false 模拟 Security 6 里忘了显式保存。 */
    @PostMapping("/login/session")
    public Map<String, Object> session(@RequestBody Cred c, @RequestParam(defaultValue = "true") boolean save,
                                       @RequestParam(defaultValue = "false") boolean rotate,
                                       HttpServletRequest req, HttpServletResponse res) {
        Authentication a = am.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(c.username(), c.password()));
        String before = req.getSession(false) == null ? null : req.getSession(false).getId();
        if (rotate && before != null) req.changeSessionId();   // 防会话固定：登录这一刻换号
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(a);
        SecurityContextHolder.setContext(ctx);
        if (save) repo.saveContext(ctx, req, res);
        HttpSession s = req.getSession(false);
        return Map.of("user", a.getName(), "sessionBefore", String.valueOf(before),
                "sessionAfter", s == null ? "null" : s.getId());
    }

    /** 方式二：JWT。验完密码签一张 15 分钟的令牌，服务端什么都不存。 */
    @PostMapping("/login/token")
    public Map<String, Object> token(@RequestBody Cred c) {
        Authentication a = am.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(c.username(), c.password()));
        return Map.of("token", issue(enc, a.getName(), Instant.now().plusSeconds(900)));
    }

    static String issue(JwtEncoder enc, String sub, Instant exp) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("trade-range").subject(sub)
                .issuedAt(now.isBefore(exp) ? now : exp.minusSeconds(900)).expiresAt(exp)
                .claim("scope", "order:pay").build();
        JwsHeader h = JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        return enc.encode(JwtEncoderParameters.from(h, claims)).getTokenValue();
    }

    @PostMapping("/logout/session")
    public Map<String, Object> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        return Map.of("loggedOut", true);
    }

    /** 公开接口：顺手建一个会话，用来演示“登录前就有的会话号”。 */
    @GetMapping("/public/hello")
    public Map<String, Object> hello(HttpServletRequest req) { return Map.of("session", req.getSession(true).getId()); }
}
