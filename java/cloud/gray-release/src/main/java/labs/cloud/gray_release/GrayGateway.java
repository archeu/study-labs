package labs.cloud.gray_release;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 全景第 4 站：按用户打灰度标记，LoadBalancer 按实例元数据 hint 挑版本。
 * 灰度名单 gray.users 放在 Nacos 配置 gateway.yaml 里，改了不重启就生效。
 */
@SpringBootApplication
@EnableConfigurationProperties(GrayGateway.GrayProps.class)
@LoadBalancerClients(defaultConfiguration = GrayGateway.HintLbConfig.class)
public class GrayGateway {
    public static void main(String[] args) { SpringApplication.run(GrayGateway.class, args); }

    @ConfigurationProperties(prefix = "gray")
    public static class GrayProps {
        private List<String> users = new ArrayList<>();
        public List<String> getUsers() { return users; }
        public void setUsers(List<String> users) { this.users = users; }
    }

    /** 先删掉外面带进来的 X-Gray（防止自己给自己开灰度），再按名单写上 v2 或 v1 */
    @Bean
    GlobalFilter grayMark(GrayProps props) {
        return new GrayMark(props);
    }

    static class GrayMark implements GlobalFilter, Ordered {
        private final GrayProps props;
        GrayMark(GrayProps props) { this.props = props; }
        @Override
        public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
            String user = ex.getRequest().getHeaders().getFirst("X-User-Id");   // 真实项目里来自上一页的 JWT 过滤器
            String ver = user != null && props.getUsers().contains(user) ? "v2" : "v1";
            var req = ex.getRequest().mutate().headers(h -> { h.remove("X-Gray"); h.set("X-Gray", ver); }).build();
            return chain.filter(ex.mutate().request(req).build());
        }
        @Override public int getOrder() { return -50; }       // 在挑实例（10150）之前
    }

    /**
     * LoadBalancer：在发现到的实例里，按请求头 X-Gray 的值筛 metadata.hint 相同的实例。
     * 顺序很要紧：缓存层要紧跟在发现层后面，hint 过滤放在最外层。
     * 写成 withHints().withCaching() 时，缓存层不把请求往下传，hint 被悄悄跳过（run.sh 第一次就是这样）。
     */
    public static class HintLbConfig {
        @Bean
        ServiceInstanceListSupplier hintSupplier(ConfigurableApplicationContext ctx) {
            return ServiceInstanceListSupplier.builder().withDiscoveryClient().withCaching().withHints().build(ctx);
        }
    }
}
