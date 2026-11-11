package labs.network.rpc_serialization_discovery_lb;

import java.io.Serializable;

/** 订单服务和库存服务共同依赖的“接口包”：相当于 gRPC 的 .proto、Dubbo 的 api 模块。 */
public final class Api {
    private Api() {}

    public interface InventoryService {
        ReserveReply reserve(ReserveRequest req);
    }

    public static final class ReserveRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long orderId;
        public final long skuId;
        public final int qty;
        public ReserveRequest(long orderId, long skuId, int qty) {
            this.orderId = orderId; this.skuId = skuId; this.qty = qty;
        }
    }

    public static final class ReserveReply implements Serializable {
        private static final long serialVersionUID = 1L;
        public final boolean ok;
        public final String instance;   // 哪台库存实例处理的，方便看负载均衡
        public ReserveReply(boolean ok, String instance) { this.ok = ok; this.instance = instance; }
        @Override public String toString() { return "ReserveReply{ok=" + ok + ", instance=" + instance + "}"; }
    }
}
