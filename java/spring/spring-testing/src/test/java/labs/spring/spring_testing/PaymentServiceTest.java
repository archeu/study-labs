package labs.spring.spring_testing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import labs.spring.spring_testing.OrderMapper.Order;
import labs.spring.spring_testing.PaymentGateway.ChannelDeclinedException;
import labs.spring.spring_testing.PaymentService.AmountMismatchException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 第一层：纯 JUnit 5 + Mockito。不起 Spring，不连库，毫秒级。
 * 测的是 doPay 的“判断和调用顺序”；测不到的是事务（这里根本没有代理）。
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock OrderMapper mapper;
    @Mock PaymentGateway gateway;
    @InjectMocks PaymentService service;     // new PaymentService(mapper, gateway)，没有代理

    @BeforeEach
    void order9527() {
        when(mapper.findById(9527)).thenReturn(new Order(9527, 1024, 79800, "CREATED"));
    }

    @Test
    @DisplayName("金额对不上：不改状态，也不去扣款")
    void amountMismatch() throws Exception {
        assertThatThrownBy(() -> service.pay(9527, 1024, 100))
                .isInstanceOf(AmountMismatchException.class);
        verify(mapper, never()).changeStatus(anyLong(), any(), any());
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("正常付款：先改状态，再按应付金额扣款，最后记流水")
    void happyPath() throws Exception {
        when(mapper.changeStatus(9527, "CREATED", "PAID")).thenReturn(1);
        when(gateway.charge(9527, 79800)).thenReturn("CH-9527");

        var r = service.pay(9527, 1024, 79800);

        assertThat(r.status()).isEqualTo("PAID");
        var inOrder = inOrder(mapper, gateway);
        inOrder.verify(mapper).changeStatus(9527, "CREATED", "PAID");
        inOrder.verify(gateway).charge(9527, 79800);
        inOrder.verify(mapper).insertLog(9527, 79800, "CH-9527");
    }

    /** 关键：对的写法和三颗雷，在这一层表现完全一样，全绿。 */
    @ParameterizedTest(name = "{0}：渠道拒绝时，这一层只看得到“抛了 / 返回 FAILED”")
    @ValueSource(strings = {"pay", "payCheckedMine", "paySwallowMine", "paySelfCallMine"})
    void declinedLooksTheSame(String method) throws Exception {
        when(mapper.changeStatus(9527, "CREATED", "PAID")).thenReturn(1);
        when(gateway.charge(9527, 79800)).thenThrow(new ChannelDeclinedException("余额不足"));

        switch (method) {
            case "pay" -> assertThatThrownBy(() -> service.pay(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
            case "payCheckedMine" -> assertThatThrownBy(() -> service.payCheckedMine(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
            case "paySwallowMine" -> assertThat(service.paySwallowMine(9527, 1024, 79800).status()).isEqualTo("FAILED");
            case "paySelfCallMine" -> assertThatThrownBy(() -> service.paySelfCallMine(9527, 1024, 79800)).isInstanceOf(ChannelDeclinedException.class);
        }
        verify(mapper).changeStatus(9527, "CREATED", "PAID");   // 状态已经改了；改了之后回没回滚，这一层看不见
        verify(mapper, never()).insertLog(anyLong(), anyLong(), any());
    }
}
