package labs.spring.spring_security_token;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * 一条安全链同时认三种凭证：Basic 头（每次验密码）、JSESSIONID（登录时验一次，之后查会话）、
 * Bearer JWT（登录时验一次，之后验签名）。哪种凭证在请求里，哪个过滤器就出手。
 */
@Configuration
@EnableMethodSecurity   // 打开 @PreAuthorize：第二道门，按方法、按数据
public class SecurityConfig {

    /** HS256 的密钥：至少 256 位。真实项目放配置中心或 KMS，这里写死只为一条命令跑完。 */
    static final SecretKey KEY = new SecretKeySpec(
            "trade-range-demo-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())   // 客户端不是浏览器才敢关；浏览器 + 会话 cookie 必须留着 CSRF，见页面⑥
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/login/**", "/public/**", "/error").permitAll()
                    .anyRequest().authenticated())
            .httpBasic(withDefaults())
            .oauth2ResourceServer(o -> o.jwt(withDefaults()));
        return http.build();
    }

    /** 密码用 DelegatingPasswordEncoder 存成 {bcrypt}$2a$10$…，和全景里“升级”之后的样子一样。 */
    @Bean
    PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean
    InMemoryUserDetailsManager users(PasswordEncoder pe) {
        String hash = pe.encode("pw");
        return new InMemoryUserDetailsManager(
                User.withUsername("1024").password(hash).authorities("order:pay").build(),
                User.withUsername("1025").password(hash).authorities("order:pay").build());
    }

    /** 登录接口自己调它：只在登录这一刻验一次密码。httpBasic 也会拿到同一个。 */
    @Bean
    AuthenticationManager authenticationManager(InMemoryUserDetailsManager users, PasswordEncoder pe) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(pe);
        p.setUserDetailsService(users);
        p.setUserDetailsPasswordService(users);
        return new ProviderManager(p);
    }

    @Bean
    SecurityContextRepository sessionRepo() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    JwtEncoder jwtEncoder() { return new NimbusJwtEncoder(new ImmutableSecret<>(KEY)); }

    @Bean
    JwtDecoder jwtDecoder() { return NimbusJwtDecoder.withSecretKey(KEY).build(); }   // 默认 HS256

    /** 排在 Security 前面的计时过滤器：记下“进门”的时刻，Controller 里再记一次，差就是认证 + 授权花的时间。 */
    @Bean
    FilterRegistrationBean<Filter> timing() {
        FilterRegistrationBean<Filter> r = new FilterRegistrationBean<>((req, res, chain) -> {
            if (((HttpServletRequest) req).getDispatcherType() == DispatcherType.REQUEST) Trace.mark("F0");
            chain.doFilter(req, res);
            if (((HttpServletRequest) req).getDispatcherType() == DispatcherType.REQUEST) Trace.mark("F1");
        });
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }
}
