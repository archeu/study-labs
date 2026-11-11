package labs.network.http_client_pool_timeouts;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 三个实验，各对应页面上的一句结论：
 *   A. 读超时管的是“两次读之间”，不是整次调用 —— 慢慢挤的响应能远远超过它
 *   B. 池子满了，请求在客户端排队；不设“拿连接超时”，就一直等
 *   C. 客户端超时 ≠ 服务端没做；超时后无脑重试，同一个订单扣两次
 */
public class TimeoutLab {

    public static void main(String[] args) throws Exception {
        try (FakeInventoryServer server = new FakeInventoryServer(0)) {
            String base = "http://127.0.0.1:" + server.port();
            experimentA(base);
            experimentB(base);
            experimentC(base, server);
        }
    }

    // ---------- A ----------
    static void experimentA(String base) throws Exception {
        System.out.println("== A. 读超时 1000 ms，服务端每 400 ms 挤 1 个字节，共 10 个 ==");
        try (CloseableHttpClient http = client(5, Timeout.ofMinutes(3), Timeout.ofMilliseconds(1000))) {
            String url = base + "/trickle?chunks=10&gap=400";
            timed("只配读超时", () -> http.execute(new HttpGet(url), r -> EntityUtils.toString(r.getEntity())));

            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
            HttpGet req = new HttpGet(url);
            ScheduledFuture<?> killer = timer.schedule(req::cancel, 1500, TimeUnit.MILLISECONDS);
            try {
                timed("再加 1500 ms 总 deadline", () -> http.execute(req, r -> EntityUtils.toString(r.getEntity())));
            } finally {
                killer.cancel(false);
                timer.shutdownNow();
            }
        }
    }

    // ---------- B ----------
    static void experimentB(String base) throws Exception {
        System.out.println("== B. 每个地址 5 条连接（默认值），20 个线程同时调，每次服务端 500 ms ==");
        runBurst(base, Timeout.ofMinutes(3), "拿连接超时 = 默认 3 分钟");
        runBurst(base, Timeout.ofMilliseconds(300), "拿连接超时 = 300 ms");
    }

    static void runBurst(String base, Timeout leaseTimeout, String label) throws Exception {
        try (CloseableHttpClient http = client(5, leaseTimeout, Timeout.ofSeconds(5))) {
            // 先预热 5 条连接，免得建连时间混进来
            warm(http, base, 5);
            ExecutorService es = Executors.newFixedThreadPool(20);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                fs.add(es.submit(() -> {
                    go.await();
                    long t0 = System.nanoTime();
                    String outcome;
                    try {
                        http.execute(new HttpGet(base + "/reserve?delay=500"), r -> EntityUtils.toString(r.getEntity()));
                        outcome = "ok";
                    } catch (Exception e) {
                        outcome = e.getClass().getSimpleName();
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    return outcome + "@" + (ms / 100 * 100); // 按 100 ms 归档
                }));
            }
            go.countDown();
            Map<String, Integer> buckets = new TreeMap<>();
            for (Future<String> f : fs) buckets.merge(f.get(), 1, Integer::sum);
            es.shutdown();
            System.out.println("  " + label + " → " + buckets);
        }
    }

    // ---------- C ----------
    static void experimentC(String base, FakeInventoryServer server) throws Exception {
        System.out.println("== C. 读超时 300 ms，库存服务这次要 500 ms；超时后业务代码手动重试一次 ==");
        int before = server.reserved.get();
        try (CloseableHttpClient http = client(5, Timeout.ofMinutes(3), Timeout.ofMilliseconds(300))) {
            String url = base + "/reserve?order=9527&delay=500";
            for (int attempt = 1; attempt <= 2; attempt++) {
                timed("第 " + attempt + " 次", () -> http.execute(new HttpGet(url), r -> EntityUtils.toString(r.getEntity())));
            }
        }
        Thread.sleep(700);
        System.out.println("  客户端两次都算失败；服务端给订单 9527 扣库存的次数：" + (server.reserved.get() - before));
    }

    // ---------- helpers ----------
    static CloseableHttpClient client(int perRoute, Timeout leaseTimeout, Timeout responseTimeout) {
        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnPerRoute(perRoute).setMaxConnTotal(perRoute * 5).build();
        return HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(leaseTimeout)
                        .setResponseTimeout(responseTimeout)
                        .build())
                .build();
    }

    static void warm(CloseableHttpClient http, String base, int n) throws Exception {
        ExecutorService es = Executors.newFixedThreadPool(n);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(es.submit(() -> http.execute(new HttpGet(base + "/reserve?delay=50"), r -> EntityUtils.toString(r.getEntity()))));
        }
        for (Future<?> f : fs) f.get();
        es.shutdown();
    }

    interface Call {
        Object run() throws Exception;
    }

    static void timed(String label, Call c) {
        long t0 = System.nanoTime();
        String r;
        try {
            Object o = c.run();
            r = "成功，收到 " + String.valueOf(o).length() + " 字节";
        } catch (Exception e) {
            r = "失败 " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        System.out.printf("  %-24s %5d ms  %s%n", label, (System.nanoTime() - t0) / 1_000_000, r);
    }
}
