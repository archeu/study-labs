package labs.spring.spring_boot4_upgrade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/** 升级前（Boot 3.5 + Jackson 2）团队写的 JSON 配置。 */
@Configuration
public class JsonConfig {

    /** 定制 Boot 自动配置的那个 ObjectMapper：统一时区。 */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer tradeJson() {
        return b -> b.timeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    /** 业务代码里手动转 JSON（比如写审计日志），Jackson 2 的异常是受检的，必须 catch。 */
    public static String toJson(ObjectMapper om, Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
