package labs.spring.spring_mvc_dispatch;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;
import java.lang.reflect.Type;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 第 3 站：Security。--lab.permit-error=true 时放行 /error；--lab.audit-in-security=true 时把 AuditFilter 也塞进安全链。 */
    @Bean
    SecurityFilterChain api(HttpSecurity http,
                            @Value("${lab.permit-error:false}") boolean permitError,
                            @Value("${lab.audit-in-security:false}") boolean auditInSecurity,
                            org.springframework.beans.factory.ObjectProvider<AuditFilter> audit) throws Exception {
        http.csrf(c -> c.disable())
            .authorizeHttpRequests(a -> {
                a.requestMatchers("/public/**").permitAll();
                if (permitError) a.requestMatchers("/error").permitAll();
                a.anyRequest().authenticated();
            })
            .httpBasic(b -> {});
        if (auditInSecurity) http.addFilterAfter(audit.getObject(), BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    InMemoryUserDetailsManager users() {
        return new InMemoryUserDetailsManager(User.withUsername("1024").password("{noop}pw").roles("BUYER").build());
    }

    /** 计时用：不加密也不“升级”成 BCrypt，免得第 3 站的 82 ms 盖住其他各站（全景页实测过）。 */
    @Bean
    @SuppressWarnings("deprecation")
    PasswordEncoder plainEncoder() {
        return new PasswordEncoder() {
            public String encode(CharSequence raw) { return raw.toString(); }
            public boolean matches(CharSequence raw, String enc) { return enc.equals("{noop}" + raw) || enc.contentEquals(raw); }
        };
    }

    /** 常见写法：一个 @Component 的普通 Filter，又被 addFilterAfter 加进安全链。 */
    @Component
    @ConditionalOnProperty("lab.audit-in-security")
    static class AuditFilter implements Filter {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
            Trace.ev(req, "AuditFilter", "记一条审计日志");
            chain.doFilter(req, res);
        }
    }

    /** 第 4、11 站：拦截器。 */
    @Override
    public void addInterceptors(InterceptorRegistry reg) {
        reg.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
                Trace.mark("P");
                String m = h instanceof HandlerMethod hm ? hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName() : h.getClass().getSimpleName();
                Trace.ev(req, "Interceptor", "preHandle → " + m);
                return true;
            }
            @Override
            public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object h, Exception ex) {
                Trace.mark("E");
                Trace.ev(req, "Interceptor", "afterCompletion status=" + res.getStatus()
                        + (ex != null ? " ex=" + ex.getClass().getSimpleName() : ""));
            }
        });
    }

    /** 第 5 站计时点：JSON 读完（还没校验）。 */
    @ControllerAdvice
    static class BodyRead extends RequestBodyAdviceAdapter {
        public boolean supports(MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> c) { return true; }
        @Override
        public Object afterBodyRead(Object body, HttpInputMessage in, MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> c) {
            Trace.mark("B");
            return body;
        }
    }

    /** 第 11 站计时点：要写 JSON 了。 */
    @ControllerAdvice
    static class BodyWrite implements ResponseBodyAdvice<Object> {
        public boolean supports(MethodParameter p, Class<? extends HttpMessageConverter<?>> c) { return true; }
        public Object beforeBodyWrite(Object body, MethodParameter p, MediaType mt, Class<? extends HttpMessageConverter<?>> c,
                                      ServerHttpRequest req, ServerHttpResponse res) {
            Trace.mark("W");
            Trace.EV.add(String.format("%-8s %-22s %s", "", "ResponseBodyAdvice", "写 " + mt + "，选中 " + c.getSimpleName()));
            return body;
        }
    }
}
