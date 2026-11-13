package labs.spring.spring_testing;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 对照组：同一组 SQL，@MybatisTest 默认把数据源换成内嵌 H2。
 * 大小写不同的流水号，H2 认为是两条，MySQL（默认排序规则不分大小写）认为重复。
 */
@MybatisTest
class OrderMapperH2Test {
    @Autowired OrderMapper mapper;

    @Test
    void 状态条件_第二次付款影响0行() {
        mapper.insertOrderForTest();
        assertThat(mapper.changeStatus(9527, "CREATED", "PAID")).isEqualTo(1);
        assertThat(mapper.changeStatus(9527, "CREATED", "PAID")).isEqualTo(0);
    }

    @Test
    void 渠道流水号只差大小写_H2放行() {
        mapper.insertLog(9527, 79800, "CH-9527-a");
        assertThat(mapper.insertLog(9527, 79800, "CH-9527-A")).isEqualTo(1);
    }
}
