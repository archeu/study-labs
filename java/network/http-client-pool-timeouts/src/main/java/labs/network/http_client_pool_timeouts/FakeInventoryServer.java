package labs.network.http_client_pool_timeouts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的库存服务，只用 JDK 自带的 HttpServer（JDK 8 起就有）。
 *
 *   /reserve?order=9527&delay=500   睡 delay 毫秒，然后“扣库存”（计数 +1），返回 ok
 *   /trickle?chunks=10&gap=400       先回响应头，再每隔 gap 毫秒挤出一个字节：
 *                                    每次读都不超过读超时，但总时长 = chunks × gap
 */
public class FakeInventoryServer implements AutoCloseable {

    /** 服务端真正扣过库存的次数：客户端超时放弃以后，这里照样会加。 */
    public final AtomicInteger reserved = new AtomicInteger();

    private final HttpServer server;
    private final ExecutorService pool = Executors.newFixedThreadPool(64);

    public FakeInventoryServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 128);
        server.createContext("/reserve", this::reserve);
        server.createContext("/trickle", this::trickle);
        server.setExecutor(pool);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void reserve(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        sleep(Long.parseLong(q.getOrDefault("delay", "0")));
        reserved.incrementAndGet();
        byte[] body = ("ok order=" + q.getOrDefault("order", "?")).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void trickle(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        int chunks = Integer.parseInt(q.getOrDefault("chunks", "10"));
        long gap = Long.parseLong(q.getOrDefault("gap", "400"));
        ex.sendResponseHeaders(200, 0);            // 0 = chunked，响应头马上发出去
        try (OutputStream out = ex.getResponseBody()) {
            for (int i = 0; i < chunks; i++) {
                sleep(gap);
                out.write('.');
                out.flush();
            }
        } catch (IOException clientGone) {
            // 客户端到了 deadline 主动断开，写失败是正常的
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
            }
        }
        return m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
