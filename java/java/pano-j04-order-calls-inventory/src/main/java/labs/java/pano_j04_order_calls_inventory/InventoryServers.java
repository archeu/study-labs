package labs.java.pano_j04_order_calls_inventory;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerTransportFilter;
import io.grpc.stub.StreamObserver;
import labs.java.pano_j04_order_calls_inventory.proto.InventoryServiceGrpc;
import labs.java.pano_j04_order_calls_inventory.proto.ReserveReply;
import labs.java.pano_j04_order_calls_inventory.proto.ReserveRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 库存服务的两种“门”：gRPC（两个实例，模拟注册中心里的两台机器）和 HTTP/1.1 + JSON（一个实例）。
 * 每个门都记下：建了几条 TCP 连接、处理了几次调用、业务代码跑在哪个线程上。
 */
final class InventoryServers {

    /** 订单 9528 模拟“库存行锁等了 500 ms”，用来看超时。 */
    static final long SLOW_ORDER = 9528L;
    static final long SLOW_MS = 500;

    static final class Stats {
        final String name;
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final Set<String> remotePorts = ConcurrentHashMap.newKeySet();
        volatile String lastIoThread = "?";
        volatile String lastBizThread = "?";
        /** 慢订单：客户端超时以后，服务端这边发生了什么 */
        volatile String slowNote = "";
        Stats(String name) { this.name = name; }
    }

    private static final AtomicLong stock = new AtomicLong(1_000_000);

    // ---------------- gRPC ----------------

    static Server startGrpc(int port, Stats st, ExecutorService bizPool) throws IOException {
        return Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .executor(bizPool)                      // 第 9 站：业务代码跑在这个池里，不在 EventLoop 上
                .addTransportFilter(new ServerTransportFilter() {
                    @Override public Attributes transportReady(Attributes a) {
                        st.connections.incrementAndGet(); // 一条 TCP 连接（一个 HTTP/2 transport）建好
                        st.lastIoThread = Thread.currentThread().getName();
                        return a;
                    }
                })
                .addService(new InventoryServiceGrpc.InventoryServiceImplBase() {
                    @Override public void reserve(ReserveRequest req, StreamObserver<ReserveReply> out) {
                        st.calls.incrementAndGet();
                        st.lastBizThread = Thread.currentThread().getName();
                        boolean slow = req.getOrderId() == SLOW_ORDER;
                        if (slow) sleep(SLOW_MS);                  // 模拟等行锁
                        long left = stock.addAndGet(-req.getQty()); // 慢订单也照样扣：业务代码没看取消信号
                        if (slow) st.slowNote = "等了 " + SLOW_MS + " ms 才干活；这时 Context.isCancelled()="
                                + Context.current().isCancelled() + "，库存照样扣了 " + req.getQty() + " 件";
                        out.onNext(ReserveReply.newBuilder().setOk(true).setLeft((int) Math.min(left, Integer.MAX_VALUE)).build());
                        out.onCompleted();
                    }
                })
                .build()
                .start();
    }

    // ---------------- HTTP/1.1 + JSON ----------------

    private static final Pattern NUM = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\\d+)");

    static HttpServer startHttp(int port, Stats st, ExecutorService bizPool) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        s.setExecutor(bizPool);
        s.createContext("/inventory/reserve", ex -> {
            st.calls.incrementAndGet();
            if (st.remotePorts.add(String.valueOf(ex.getRemoteAddress().getPort()))) st.connections.incrementAndGet();
            st.lastBizThread = Thread.currentThread().getName();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int qty = 0;
            Matcher m = NUM.matcher(body);
            while (m.find()) if (m.group(1).equals("qty")) qty = Integer.parseInt(m.group(2));
            long left = stock.addAndGet(-qty);
            byte[] resp = ("{\"ok\":true,\"left\":" + left + "}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        s.start();
        return s;
    }

    static ExecutorService namedPool(String prefix, int n) {
        AtomicInteger i = new AtomicInteger();
        return Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, prefix + "-" + i.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private InventoryServers() {}
}
