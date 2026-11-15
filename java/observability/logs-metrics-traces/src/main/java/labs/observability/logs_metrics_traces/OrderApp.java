package labs.observability.logs_metrics_traces;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/**
 * 交易靶场 order-svc 的最小版：一个下单请求，同时产出指标、日志、链路三样数据。
 * 为了不多起一个进程，“商品服务”也在这个应用里：/api/products/{sku}，下单时用 HTTP 调自己。
 */
@SpringBootApplication
public class OrderApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderApp.class, args);
    }

    /** 用 Spring 注入的 RestClient.Builder 建客户端，它带着观测（Observation），出站请求会有 span、会带 traceparent 头。 */
    @Bean
    RestClient productClient(RestClient.Builder builder, @Value("${server.port}") int port) {
        return builder.baseUrl("http://localhost:" + port).build();
    }

    /** 雷：普通线程池。任务换了线程，ThreadLocal 里的 trace 上下文没跟过去。 */
    @Bean
    ThreadPoolTaskExecutor rawPool() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setThreadNamePrefix("raw-");
        return e;
    }

    /** 正确做法：提交任务时拍一张上下文快照，在新线程里恢复（Spring 6.1+ 自带）。 */
    @Bean
    ThreadPoolTaskExecutor ctxPool() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setThreadNamePrefix("ctx-");
        e.setTaskDecorator(new ContextPropagatingTaskDecorator());
        return e;
    }
}
