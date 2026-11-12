package labs.spring.spring_mvc_dispatch;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 三种写法的过滤器，看它们各在哪几种分派里出场。 */
@Configuration
public class LabFilters {

    /** 写法一：普通 Filter + @Component。Boot 替它注册，没指定分派类型。排最外面，顺便计时。 */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class PlainFilter implements Filter {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
            Trace.mark("F0");
            Trace.ev(req, "PlainFilter", "进 " + ((HttpServletRequest) req).getRequestURI());
            if ("filter".equals(((HttpServletRequest) req).getHeader("X-Boom")))
                throw new IllegalStateException("过滤器里抛的异常");
            try { chain.doFilter(req, res); }
            finally {
                Trace.mark("F1");
                Trace.ev(req, "PlainFilter", "出 status=" + ((HttpServletResponse) res).getStatus()
                        + " committed=" + res.isCommitted());
            }
        }
    }

    /** 写法二：继承 OncePerRequestFilter + @Component。 */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    static class OnceFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            Trace.ev(req, "OnceFilter", "进");
            chain.doFilter(req, res);
        }
    }

    /** 写法三：普通 Filter，用 FilterRegistrationBean 明确登记 REQUEST + ERROR 两种分派。 */
    static class ErrorAwareFilter implements Filter {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
            Trace.ev(req, "ErrorAwareFilter", "进" + (req.getDispatcherType() == DispatcherType.ERROR
                    ? " 原始 URI=" + req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)
                    + " 原始状态=" + req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) : ""));
            chain.doFilter(req, res);
        }
    }

    @Bean
    FilterRegistrationBean<ErrorAwareFilter> errorAwareFilter() {
        var r = new FilterRegistrationBean<>(new ErrorAwareFilter());
        r.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        r.addUrlPatterns("/*");
        return r;
    }
}
