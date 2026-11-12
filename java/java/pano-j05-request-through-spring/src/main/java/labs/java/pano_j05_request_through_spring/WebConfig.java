package labs.java.pano_j05_request_through_spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 第 3 站：一个自己写的过滤器，排在 Security 前面，量整个请求在 Spring 里花了多久。 */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class TimingFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            long t0 = System.nanoTime();
            Trace.MARK.clear(); Trace.mark("F0");
            Trace.ev("③ 过滤器", "TimingFilter 进：" + req.getMethod() + " " + req.getRequestURI());
            try { chain.doFilter(req, res); }
            finally {
                Trace.mark("F1");
                Trace.ev("⑪ 回程", "TimingFilter 出：status=" + res.getStatus() + "，用时 "
                        + String.format("%.2f ms", (System.nanoTime() - t0) / 1e6));
            }
        }
    }

    /** 第 3 站：Security 的过滤器链。HTTP Basic 认出用户 1024。 */
    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(b -> {})
                .build();
    }

    @Bean
    InMemoryUserDetailsManager users() {
        return new InMemoryUserDetailsManager(User.withUsername("1024").password("{noop}pw").roles("BUYER").build());
    }

    /**
     * 对照用：-Dlab.fast-auth=true 时换成不加密、也不“升级”的编码器。
     * 默认情况下，第一次登录成功后 DaoAuthenticationProvider 会把 {noop} 密码升级成 {bcrypt}，
     * 之后每个带 Basic 头的请求都要算一次 BCrypt。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("lab.fast-auth")
    @SuppressWarnings("deprecation")
    org.springframework.security.crypto.password.PasswordEncoder plainEncoder() {
        return new org.springframework.security.crypto.password.PasswordEncoder() {
            public String encode(CharSequence raw) { return raw.toString(); }
            public boolean matches(CharSequence raw, String enc) { return enc.equals("{noop}" + raw) || enc.contentEquals(raw); }
        };
    }

    /** 第 4、11 站：拦截器在 DispatcherServlet 里面，已经知道要调哪个 Controller 方法。 */
    @Override
    public void addInterceptors(InterceptorRegistry reg) {
        reg.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
                Trace.mark("P");
                String m = h instanceof HandlerMethod hm ? hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName() : h.toString();
                Trace.ev("④ 拦截器", "preHandle：要调的是 " + m);
                return true;
            }
            @Override
            public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object h, Exception ex) {
                Trace.mark("E");
                Trace.ev("⑪ 回程", "afterCompletion：status=" + res.getStatus());
            }
        });
    }
}
