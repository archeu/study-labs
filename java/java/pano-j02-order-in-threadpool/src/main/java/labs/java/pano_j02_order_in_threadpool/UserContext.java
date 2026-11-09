package labs.java.pano_j02_order_in_threadpool;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 第 3、11 站：当前用户绑在“处理这个请求的线程”上，请求结束必须清掉。 */
@Component
class UserContext extends OncePerRequestFilter {
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();

    static Long get() {
        return USER.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String u = req.getHeader("X-User");
        if (u != null) USER.set(Long.valueOf(u));
        try {
            chain.doFilter(req, res);
        } finally {
            USER.remove();   // 第 11 站：线程要回池给下一个请求用，不清就串号
        }
    }
}
