package labs.spring.spring_testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/**
 * 真 MySQL：容器是一个 bean，跟着 Spring 测试上下文走。
 * 上下文被缓存复用，容器也就只起一次；@ServiceConnection 把 JDBC 地址、账号接进 DataSource。
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {
    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>("mysql:8.4");
    }
}
