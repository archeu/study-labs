package labs.spring.spring_testing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import labs.spring.spring_testing.PaymentGateway.ChannelDeclinedException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 坑：测试类上加 @Transactional（每个测试结束自动回滚，看着很干净）。
 * 业务方法的 REQUIRED 事务会加入测试的大事务，回滚要等测试结束才发生；
 * 测试里查到的是同一个事务里还没撤掉的数据。对的写法和雷 1 看起来一模一样。
 */
@SpringBootTest
@Import(MySqlContainerConfig.class)
@Transactional
class TransactionalTestTrapTest {
    @Autowired PaymentService service;
    @Autowired OrderMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM pay_log");
        jdbc.update("DELETE FROM orders");
        jdbc.update("INSERT INTO orders VALUES (9527, 1024, 79800, 'CREATED')");
        given(gateway.charge(9527, 79800)).willThrow(new ChannelDeclinedException("余额不足"));
    }

    @Test
    void 对的写法_在测试事务里_看起来也没回滚() {
        assertThatThrownBy(() -> service.pay(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
        assertThat(mapper.findById(9527).status()).isEqualTo("PAID");   // 误导！真实运行时是 CREATED
    }

    @Test
    void 雷1_在测试事务里_看起来一样() {
        assertThatThrownBy(() -> service.payCheckedMine(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
        assertThat(mapper.findById(9527).status()).isEqualTo("PAID");
    }

    @Test
    void 雷2_在测试事务里_看起来一样() {
        assertThat(service.paySwallowMine(9527, 1024, 79800).status()).isEqualTo("FAILED");
        assertThat(mapper.findById(9527).status()).isEqualTo("PAID");
    }

    @Test
    void 雷3_在测试事务里_看起来一样() {
        assertThatThrownBy(() -> service.paySelfCallMine(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
        assertThat(mapper.findById(9527).status()).isEqualTo("PAID");
    }
}
