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

/**
 * 第三层：整条链路的后半段（第 6～9 站）跑在真 MySQL 上。
 * 只有外部渠道是假的；代理、事务拦截器、连接、SQL 全是真的。
 * 雷的测试写成“断言雷确实炸了”，修好以后把断言反过来。
 */
@SpringBootTest
@Import(MySqlContainerConfig.class)
class PaymentTxMySqlTest {
    @Autowired PaymentService service;      // 这是 CGLIB 代理
    @Autowired OrderMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentGateway gateway;   // 渠道拒绝

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM pay_log");
        jdbc.update("DELETE FROM orders");
        jdbc.update("INSERT INTO orders VALUES (9527, 1024, 79800, 'CREATED')");
        given(gateway.charge(9527, 79800)).willThrow(new ChannelDeclinedException("余额不足"));
    }

    String status() { return mapper.findById(9527).status(); }

    @Test
    void 对的写法_渠道拒绝_订单回到待付款() {
        assertThatThrownBy(() -> service.pay(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
        assertThat(status()).isEqualTo("CREATED");
    }

    @Test
    void 雷1_受检异常_没回滚() {
        assertThatThrownBy(() -> service.payCheckedMine(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
        assertThat(status()).as("钱没扣到，订单却是已付款").isEqualTo("PAID");
    }

    @Test
    void 雷2_自己catch掉_没回滚() {
        assertThat(service.paySwallowMine(9527, 1024, 79800).status()).isEqualTo("FAILED");
        assertThat(status()).as("接口说失败，库里却是已付款").isEqualTo("PAID");
    }

    @Test
    void 雷3_自调用_没回滚() {
        assertThatThrownBy(() -> service.paySelfCallMine(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
        assertThat(status()).as("this.pay() 绕过了代理").isEqualTo("PAID");
    }

    @Test
    void 看一眼_注入的是代理_连的是真MySQL() throws Exception {
        assertThat(service.getClass().getName()).contains("$$SpringCGLIB$$");
        try (var c = jdbc.getDataSource().getConnection()) {
            System.out.println("[lab] PaymentService 类 = " + service.getClass().getSimpleName()
                    + "，数据库 = " + c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion()
                    + "，JDBC URL = " + c.getMetaData().getURL());
        }
    }
}
