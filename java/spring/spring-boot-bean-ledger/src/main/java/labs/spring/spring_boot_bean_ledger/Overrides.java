package labs.spring.spring_boot_bean_ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 实验台用：两种“自己动手 new 一个”的写法，平时不生效。 */
@Configuration
// 整个类也加条件：默认不登记，基线才和全景页的 249 个对得上
@ConditionalOnExpression("${lab.own-ds:false} or ${lab.own-om:false}")
public class Overrides {

    /** -Dlab.own-ds=true：自己建连接池，只给了 URL。 */
    @Bean
    @ConditionalOnProperty("lab.own-ds")
    DataSource dataSource() {
        var ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:trade;MODE=MySQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    /** -Dlab.own-om=true：自己 new 一个 ObjectMapper。 */
    @Bean
    @ConditionalOnProperty("lab.own-om")
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
