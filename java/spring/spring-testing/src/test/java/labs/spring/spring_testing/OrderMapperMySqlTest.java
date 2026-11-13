package labs.spring.spring_testing;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatis 切片 + 真 MySQL：只建 DataSource、SqlSessionFactory、Mapper（第 8、9 站）。
 * replace = NONE：别把我们的 MySQL 换成内嵌库。@MybatisTest 自带 @Transactional，每个测试结束回滚。
 */
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MySqlContainerConfig.class)
class OrderMapperMySqlTest {
    @Autowired OrderMapper mapper;

    @Test
    void 状态条件_第二次付款影响0行() {
        mapper.insertOrderForTest();
        assertThat(mapper.changeStatus(9527, "CREATED", "PAID")).isEqualTo(1);
        assertThat(mapper.changeStatus(9527, "CREATED", "PAID")).isEqualTo(0);
    }

    @Test
    void 渠道流水号只差大小写_MySQL判重复() {
        mapper.insertLog(9527, 79800, "CH-9527-a");
        assertThatThrownBy(() -> mapper.insertLog(9527, 79800, "CH-9527-A"))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
