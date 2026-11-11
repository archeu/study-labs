package labs.network.http_client_pool_timeouts;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 页面⑤：订单服务调库存服务的 HTTP 客户端，四个超时 + 一个总 deadline 都配上。
 *
 * 数字按 J04 全景的场景定：同机房，库存服务 p99 约 50 ms，订单这一步的总预算 300 ms，
 * 高峰每秒调 400 次。
 */
public class InventoryClient implements AutoCloseable {

    // 一个进程一个客户端：池子、线程、定时器都在里面，千万别每次请求 new
    private final CloseableHttpClient http;
    private final PoolingHttpClientConnectionManager pool;
    private final ScheduledExecutorService deadlineTimer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "http-deadline");
                t.setDaemon(true);
                return t;
            });
    private final String base;
    private final long deadlineMs;

    public InventoryClient(String base, long deadlineMs) {
        this.base = base;
        this.deadlineMs = deadlineMs;

        pool = PoolingHttpClientConnectionManagerBuilder.create()
                // 池子大小：400 次/秒 × 0.05 秒 × 1.5 ≈ 30（Little 定律 + 余量）。默认只有 5 / 25
                .setMaxConnPerRoute(30)
                .setMaxConnTotal(100)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        // ② 建连接：同机房一次握手 < 1 ms，100 ms 足够；默认 3 分钟
                        .setConnectTimeout(Timeout.ofMilliseconds(100))
                        // ④ 读超时的兜底值（两次读之间最多隔多久）；默认不设 = 无限
                        .setSocketTimeout(Timeout.ofMilliseconds(250))
                        // 空闲超过 2 秒的连接，复用前先检查一下有没有被对面关掉
                        .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                        // 连接最多活 5 分钟，让 DNS 变更、扩容后的新实例能分到流量
                        .setTimeToLive(TimeValue.ofMinutes(5))
                        .build())
                .build();

        http = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        // ① 从池里拿连接最多等多久；默认 3 分钟
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(50))
                        // ④ 等响应、读响应时，两次读之间最多隔多久；不是总时长
                        .setResponseTimeout(Timeout.ofMilliseconds(250))
                        .build())
                // 后台线程定期清掉空闲 30 秒以上的连接：比服务端的空闲超时（Tomcat 60 秒）短
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .evictExpiredConnections()
                // 预占库存不是幂等的 GET 语义，重试交给业务层按 order_id 去重后再做
                .disableAutomaticRetries()
                .build();
    }

    /** 带总 deadline 的一次调用：到点了就 cancel，不管卡在哪一步。 */
    public String reserve(long orderId, long serverDelayMs) throws IOException {
        return get("/reserve?order=" + orderId + "&delay=" + serverDelayMs);
    }

    public String get(String pathAndQuery) throws IOException {
        HttpGet req = new HttpGet(base + pathAndQuery);
        // ⑤ 总 deadline：HttpClient 5 的 classic API 没有“整次调用最多多久”这个参数，自己加
        ScheduledFuture<?> killer = deadlineTimer.schedule(req::cancel, deadlineMs, TimeUnit.MILLISECONDS);
        try {
            return http.execute(req, resp -> EntityUtils.toString(resp.getEntity()));
        } catch (IOException e) {
            // cancel 掉的请求抛的是 SocketException: Socket closed，换成能看懂的超时异常
            if (req.isCancelled()) {
                SocketTimeoutException te = new SocketTimeoutException("deadline " + deadlineMs + " ms exceeded");
                te.initCause(e);
                throw te;
            }
            throw e;
        } finally {
            killer.cancel(false);
        }
    }

    public String poolStats() {
        return pool.getTotalStats().toString();
    }

    @Override
    public void close() throws IOException {
        deadlineTimer.shutdownNow();
        http.close();
    }

    public static void main(String[] args) throws Exception {
        try (FakeInventoryServer server = new FakeInventoryServer(0);
             InventoryClient client = new InventoryClient("http://127.0.0.1:" + server.port(), 300)) {
            call("正常：库存服务 5 ms 就回", () -> client.reserve(9527, 5));
            call("库存服务卡 1 秒才回", () -> client.reserve(9528, 1000));
            call("响应头很快，body 每 100 ms 挤一个字节，挤 10 次", () -> client.get("/trickle?chunks=10&gap=100"));
            System.out.println("池子：" + client.poolStats());
            Thread.sleep(1200);
            System.out.println("服务端实际扣库存次数：" + server.reserved.get() + "（9528 那次客户端已经放弃，服务端照样扣了）");
        }
    }

    interface Call {
        String run() throws Exception;
    }

    static void call(String label, Call c) {
        long t0 = System.nanoTime();
        String result;
        try {
            result = "成功 " + c.run();
        } catch (Exception e) {
            result = "失败 " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        System.out.printf("%-34s %5d ms  %s%n", label, (System.nanoTime() - t0) / 1_000_000, result);
    }
}
