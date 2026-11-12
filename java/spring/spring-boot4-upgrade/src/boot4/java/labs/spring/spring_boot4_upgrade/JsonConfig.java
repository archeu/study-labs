package labs.spring.spring_boot4_upgrade;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.TimeZone;

/** 升级后（Boot 4 + Jackson 3）同一份配置。 */
@Configuration
public class JsonConfig {

    /** 定制器改名：Jackson2ObjectMapperBuilderCustomizer → JsonMapperBuilderCustomizer，拿到的是 JsonMapper.Builder。 */
    @Bean
    JsonMapperBuilderCustomizer tradeJson() {
        return b -> b.defaultTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    /** Jackson 3 的 JacksonException 是非受检异常，不用 catch 了；ObjectMapper 不可变，只能用 builder 改配置。 */
    public static String toJson(JsonMapper om, Object o) {
        return om.writeValueAsString(o);
    }
}
