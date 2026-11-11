package labs.java.pano_j04_order_calls_inventory;

import com.sun.net.httpserver.HttpServer;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import labs.java.pano_j04_order_calls_inventory.proto.InventoryServiceGrpc;
import labs.java.pano_j04_order_calls_inventory.proto.ReserveReply;
import labs.java.pano_j04_order_calls_inventory.proto.ReserveRequest;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * J04 全景：订单服务调库存服务。用户 1024 下单买 2 件 88 号商品，订单 9527，
 * 订单服务调 InventoryService.Reserve(order_id=9527, sku_id=88, qty=2)，deadline 300 ms。
 *
 * 一次跑完：序列化大小、第一次和稳定后的耗时（gRPC 对 HTTP/1.1+JSON）、
 * 50 个线程并发时各建了几条连接、负载均衡分到两台的次数、线程名、HTTP/2 帧、超时。
 * 全在本机回环上跑，延迟只看相对大小。
 */
public class OrderCallsInventory {

    static final int GRPC_A = 50051, GRPC_B = 50052, HTTP = 18090;
    static final int WARM = 3000, CONC_THREADS = 50, CONC_CALLS = 2000;

    public static void main(String[] args) throws Exception {
        try { run(args); } finally { System.exit(0); } // HttpServer 的 dispatcher 不是守护线程，出异常也要能退出
    }

    static void run(String[] args) throws Exception {
        // JDK 自带的 HttpServer 默认不开 TCP_NODELAY（sun.net.httpserver.nodelay=false），
        // 和客户端的延迟 ACK 撞在一起，每次调用多等约 40 ms。传参数 nagle 可以复现这个坑。
        boolean nagle = args.length > 0 && args[0].equals("nagle");
        if (!nagle) System.setProperty("sun.net.httpserver.nodelay", "true");
        System.out.println("HttpServer TCP_NODELAY = " + !nagle);

        // ---------- 库存服务：两个 gRPC 实例 + 一个 HTTP 实例 ----------
        var a = new InventoryServers.Stats("gRPC 实例 A :" + GRPC_A);
        var b = new InventoryServers.Stats("gRPC 实例 B :" + GRPC_B);
        var h = new InventoryServers.Stats("HTTP 实例 :" + HTTP);
        ExecutorService biz = InventoryServers.namedPool("inventory-biz", 16);
        Server sa = InventoryServers.startGrpc(GRPC_A, a, biz);
        Server sb = InventoryServers.startGrpc(GRPC_B, b, biz);
        HttpServer sh = InventoryServers.startHttp(HTTP, h, biz);

        // ---------- 第 2 站：服务发现。用一个假的“注册中心”把 inventory 解析成两个地址 ----------
        NameResolverRegistry.getDefaultRegistry().register(new FakeRegistry());
        ManagedChannel ch = Grpc.newChannelBuilder("registry:///inventory", InsecureChannelCredentials.create())
                .defaultLoadBalancingPolicy("round_robin")   // 第 2 站：轮询两台
                .build();
        var stub = InventoryServiceGrpc.newBlockingStub(ch);  // 第 1 站：stub 让远程调用看起来像本地方法

        ReserveRequest req = ReserveRequest.newBuilder().setOrderId(9527).setSkuId(88).setQty(2).build();
        String json = "{\"orderId\":9527,\"skuId\":88,\"qty\":2}";

        // ---------- 第 3 站：序列化 ----------
        byte[] pb = req.toByteArray();
        System.out.println("== 第 3 站 序列化 ==");
        System.out.println("protobuf " + pb.length + " 字节: " + HexFormat.ofDelimiter(" ").formatHex(pb));
        System.out.println("JSON     " + json.getBytes(StandardCharsets.UTF_8).length + " 字节: " + json);

        // ---------- 第 4 站：第一次调用（要建连接）和稳定后的调用 ----------
        System.out.println("\n== 第 4 站 第一次调用 vs 稳定后 ==");
        // 冷启动的第一次：要加载 Netty、protobuf、gRPC 的几千个类，还要解析名字、建两条连接、做 HTTP/2 的开场。
        // deadline 照样是 300 ms，看它够不够。
        long t0 = System.nanoTime();
        try {
            ReserveReply r = stub.withDeadlineAfter(300, TimeUnit.MILLISECONDS).reserve(req);
            System.out.printf("gRPC 第一次: %.1f ms（ok=%s）%n", ms(t0), r.getOk());
        } catch (StatusRuntimeException e) {
            System.out.printf("gRPC 第一次: %.1f ms，失败 %s%n", ms(t0), e.getStatus().getCode());
        }
        t0 = System.nanoTime();
        stub.withDeadlineAfter(300, TimeUnit.MILLISECONDS).reserve(req);
        System.out.printf("gRPC 第二次: %.1f ms%n", ms(t0));

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(200)).build();
        HttpRequest hreq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HTTP + "/inventory/reserve"))
                .timeout(Duration.ofMillis(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        t0 = System.nanoTime();
        http.send(hreq, HttpResponse.BodyHandlers.ofString());
        System.out.printf("HTTP/1.1 第一次: %.1f ms%n", ms(t0));
        t0 = System.nanoTime();
        http.send(hreq, HttpResponse.BodyHandlers.ofString());
        System.out.printf("HTTP/1.1 第二次: %.1f ms%n", ms(t0));

        double[] g = new double[WARM], hh = new double[WARM];
        for (int i = 0; i < WARM; i++) {                     // 交替跑，预热 JIT 也公平
            long s = System.nanoTime();
            stub.withDeadlineAfter(300, TimeUnit.MILLISECONDS).reserve(req);
            g[i] = ms(s);
            s = System.nanoTime();
            http.send(hreq, HttpResponse.BodyHandlers.ofString());
            hh[i] = ms(s);
        }
        // 只看后一半（JIT 已经热了）
        System.out.printf("gRPC     稳定后 p50 %.3f ms, p99 %.3f ms%n", pct(g, 50), pct(g, 99));
        System.out.printf("HTTP/1.1 稳定后 p50 %.3f ms, p99 %.3f ms%n", pct(hh, 50), pct(hh, 99));

        // ---------- 第 4、5 站：50 个线程同时调，各要几条连接 ----------
        System.out.println("\n== 第 4、5 站 50 个线程并发 " + CONC_CALLS + " 次 ==");
        ExecutorService callers = InventoryServers.namedPool("order-caller", CONC_THREADS);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < CONC_CALLS; i++) {
            fs.add(callers.submit(() -> stub.withDeadlineAfter(300, TimeUnit.MILLISECONDS).reserve(req)));
            fs.add(callers.submit(() -> http.send(hreq, HttpResponse.BodyHandlers.ofString())));
        }
        for (Future<?> f : fs) f.get();
        for (var st : List.of(a, b, h))
            System.out.printf("%-18s 连接 %3d 条, 调用 %5d 次, IO 线程 %s, 业务线程 %s%n",
                    st.name, st.connections.get(), st.calls.get(),
                    st == h ? "（HttpServer 自己的 dispatcher）" : st.lastIoThread, st.lastBizThread);

        System.out.println("\n== 第 4 站 类都加载过了，再各新建一条连接 ==");
        ManagedChannel fresh = Grpc.newChannelBuilderForAddress("127.0.0.1", GRPC_A, InsecureChannelCredentials.create()).build();
        t0 = System.nanoTime();
        InventoryServiceGrpc.newBlockingStub(fresh).withDeadlineAfter(300, TimeUnit.MILLISECONDS).reserve(req);
        System.out.printf("gRPC     新连接的第一次（类已加载）: %.2f ms%n", ms(t0));
        fresh.shutdownNow();
        HttpClient http2 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        t0 = System.nanoTime();
        http2.send(hreq, HttpResponse.BodyHandlers.ofString());
        System.out.printf("HTTP/1.1 新连接的第一次（类已加载）: %.2f ms%n", ms(t0));


        // ---------- 第 5、6 站：打开 Netty 的帧日志，看一次调用在线上是几帧 ----------
        System.out.println("\n== 第 5、6 站 一次调用的 HTTP/2 帧（客户端视角）==");
        Logger fl = Logger.getLogger("io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler");
        ConsoleHandler ch2 = new ConsoleHandler();
        ch2.setLevel(Level.FINE);
        ch2.setFormatter(new SimpleFormatter() {
            @Override public String format(java.util.logging.LogRecord rec) {
                String m = rec.getMessage();
                if (rec.getParameters() != null) m = java.text.MessageFormat.format(m, rec.getParameters());
                return "  [" + Thread.currentThread().getName() + "] " + m.replaceAll("\\s+", " ") + "\n";
            }
        });
        fl.addHandler(ch2);
        fl.setLevel(Level.FINE);
        stub.withDeadlineAfter(300, TimeUnit.MILLISECONDS).reserve(req);
        Thread.sleep(100);
        fl.setLevel(Level.INFO);
        fl.removeHandler(ch2);

        // ---------- 第 10 站：超时。订单 9528 在库存那边要等 500 ms ----------
        System.out.println("\n== 第 10 站 deadline 300 ms，库存那边要 500 ms ==");
        ReserveRequest slow = req.toBuilder().setOrderId(InventoryServers.SLOW_ORDER).build();
        t0 = System.nanoTime();
        try {
            stub.withDeadlineAfter(300, TimeUnit.MILLISECONDS).reserve(slow);
        } catch (StatusRuntimeException e) {
            System.out.printf("客户端 %.0f ms 后收到: %s%n", ms(t0), e.getStatus());
        }
        Thread.sleep(400);
        for (var st : List.of(a, b))
            if (!st.slowNote.isEmpty()) System.out.println("服务端（" + st.name + "）: " + st.slowNote);

        callers.shutdownNow();
        ch.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        sa.shutdownNow(); sb.shutdownNow(); sh.stop(0); biz.shutdownNow();
    }

    static double ms(long start) { return (System.nanoTime() - start) / 1e6; }

    static double pct(double[] xs, int p) {
        double[] c = Arrays.copyOfRange(xs, xs.length / 2, xs.length);
        Arrays.sort(c);
        return c[Math.min(c.length - 1, (int) Math.ceil(c.length * p / 100.0) - 1)];
    }

    /** 假注册中心：scheme 是 registry，名字 inventory 解析成两个实例的地址。真项目里这一步是 Nacos、K8s DNS。 */
    static final class FakeRegistry extends NameResolverProvider {
        @Override protected boolean isAvailable() { return true; }
        @Override protected int priority() { return 5; }
        @Override public String getDefaultScheme() { return "registry"; }
        @Override public NameResolver newNameResolver(URI uri, NameResolver.Args args) {
            if (!"registry".equals(uri.getScheme())) return null;
            return new NameResolver() {
                @Override public String getServiceAuthority() { return "inventory"; }
                @Override public void start(Listener2 l) {
                    l.onResult(ResolutionResult.newBuilder().setAddresses(List.of(
                            new EquivalentAddressGroup(new InetSocketAddress("127.0.0.1", GRPC_A)),
                            new EquivalentAddressGroup(new InetSocketAddress("127.0.0.1", GRPC_B)))).build());
                }
                @Override public void shutdown() {}
            };
        }
    }
}
