package labs.spring.spring_testing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import labs.spring.spring_testing.PaymentService.AmountMismatchException;
import labs.spring.spring_testing.PaymentService.PayResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 第二层：Web 切片。只建 MVC 相关的 bean（Controller、过滤器链、消息转换器），
 * Service 换成 Mockito 假货。覆盖第 3、4、5、11 站；不起 Tomcat，第 2 站不在里面。
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerWebMvcTest {
    @Autowired MockMvc mvc;
    @MockitoBean PaymentService payments;

    static final String BODY = """
            {"channel":"ALIPAY","amountCents":79800}""";

    @Test
    void 没登录_401_请求到不了Controller() throws Exception {
        mvc.perform(post("/orders/9527/pay").contentType(MediaType.APPLICATION_JSON).content(BODY))
           .andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @Test
    @WithMockUser(username = "1024")
    void 金额为0_校验挡在第5站_400() throws Exception {
        mvc.perform(post("/orders/9527/pay").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"ALIPAY\",\"amountCents\":0}"))
           .andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }

    @Test
    @WithMockUser(username = "1024")
    void 正常付款_用户名变成userId_返回JSON() throws Exception {
        given(payments.pay(9527, 1024, 79800)).willReturn(new PayResult(9527, "PAID", "CH-9527"));
        mvc.perform(post("/orders/9527/pay").contentType(MediaType.APPLICATION_JSON).content(BODY))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("PAID"))
           .andExpect(jsonPath("$.channelTxn").value("CH-9527"));
    }

    @Test
    @WithMockUser(username = "1024")
    void 金额不符_异常变成409() throws Exception {
        given(payments.pay(anyLong(), anyLong(), anyLong())).willThrow(new AmountMismatchException("金额不符"));
        mvc.perform(post("/orders/9527/pay").contentType(MediaType.APPLICATION_JSON).content(BODY))
           .andExpect(status().isConflict());
    }
}
