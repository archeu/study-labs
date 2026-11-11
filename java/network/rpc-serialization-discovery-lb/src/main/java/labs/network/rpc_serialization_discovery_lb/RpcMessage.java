package labs.network.rpc_serialization_discovery_lb;

import java.io.Serializable;

/** RPC 层自己的两种消息：请求说“调哪个服务的哪个方法、参数是什么”，响应带回结果或错误。 */
public final class RpcMessage {
    private RpcMessage() {}

    public static final class Request implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String service, method;
        public final Object[] args;
        public Request(String service, String method, Object[] args) {
            this.service = service; this.method = method; this.args = args;
        }
    }

    public static final class Response implements Serializable {
        private static final long serialVersionUID = 1L;
        public final Object value;
        public final String error;
        private Response(Object value, String error) { this.value = value; this.error = error; }
        public static Response ok(Object v) { return new Response(v, null); }
        public static Response fail(String e) { return new Response(null, e); }
    }
}
