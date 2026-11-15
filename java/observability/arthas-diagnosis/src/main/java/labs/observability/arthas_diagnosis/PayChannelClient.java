package labs.observability.arthas_diagnosis;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class PayChannelClient {

    private final RestClient http;

    PayChannelClient(RestClient payChannelRestClient) {
        this.http = payChannelRestClient;
    }

    public Map<?, ?> queryOrder(long orderId) {
        return http.get().uri("/channel/query?orderId={id}", orderId).retrieve().body(Map.class);
    }
}
