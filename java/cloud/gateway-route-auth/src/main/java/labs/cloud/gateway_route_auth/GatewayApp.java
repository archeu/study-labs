package labs.cloud.gateway_route_auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** 全景第 2～4 站：鉴权（全局过滤器）、路由（application.yml）、lb:// 交给 LoadBalancer。 */
@SpringBootApplication
public class GatewayApp {
    public static void main(String[] args) { SpringApplication.run(GatewayApp.class, args); }

    /** 第 2 站：验 JWT，先删掉外面带进来的 X-User-Id，再写上验出来的那个 */
    @Bean
    GlobalFilter authFilter() {
        return new OrderedAuth();
    }

    static class OrderedAuth implements GlobalFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
            // 演示用：把处理这个请求的线程名放进响应头（Reactor Netty 的事件循环线程）
            ex.getResponse().getHeaders().add("X-Gw-Thread", Thread.currentThread().getName());
            String auth = ex.getRequest().getHeaders().getFirst("Authorization");
            String user = auth != null && auth.startsWith("Bearer ")
                    ? Jwt.verify(auth.substring(7), System.currentTimeMillis() / 1000) : null;
            if (user == null) {
                ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return ex.getResponse().setComplete();          // 不往后转发
            }
            ServerHttpRequest req = ex.getRequest().mutate()
                    .headers(h -> { h.remove("X-User-Id"); h.set("X-User-Id", user); })
                    .build();
            return chain.filter(ex.mutate().request(req).build());
        }
        @Override public int getOrder() { return -100; }        // 越小越先执行
    }

    /**
     * 对照实验：同样“查一次 Redis 要 200 ms”，阻塞写法和非阻塞写法。
     * 只对带 X-Lab-Mode 头的请求生效。
     */
    @Bean
    GlobalFilter labFilter() {
        return new GlobalFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
                String mode = ex.getRequest().getHeaders().getFirst("X-Lab-Mode");
                if ("block".equals(mode)) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { }   // 卡住 Netty 事件循环线程
                    return chain.filter(ex);
                }
                if ("async".equals(mode)) {
                    return Mono.delay(Duration.ofMillis(200)).then(chain.filter(ex));   // 200 ms 后再继续，线程不被占
                }
                return chain.filter(ex);
            }
        };
    }
}
