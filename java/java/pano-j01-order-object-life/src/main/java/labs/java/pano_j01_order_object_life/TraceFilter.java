package labs.java.pano_j01_order_object_life;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 第 1 站和第 10、11 站：请求字节进来、响应字节写完，都在这个过滤器的前后。 */
@Component
public class TraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("labs.trace");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        Trace.begin();
        Trace.mark(1, "Tomcat 线程 " + Thread.currentThread().getName()
                + " 收到 " + req.getMethod() + " " + req.getRequestURI()
                + "，请求体 " + req.getContentLength() + " 字节，输入流 " + req.getInputStream().getClass().getSimpleName());
        chain.doFilter(req, resp);
        Trace.mark(11, "响应 " + resp.getStatus() + " 已交给 Tomcat 写回连接");
        log.info("{} {}\n{}", req.getMethod(), req.getRequestURI(), String.join("\n", Trace.end()));
    }
}
