package labs.observability.prometheus_alerting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 凌晨的下单流量：每秒 rate 次，轮流发给几个实例（相当于网关平均分）。
 * 每 10 秒打印一次这 10 秒里 200 和 500 各多少。
 * 用法：LoadGen <每秒几次> <持续秒数> <端口1,端口2,...>
 */
public class LoadGen {
    public static void main(String[] args) throws Exception {
        int rate = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        String[] ports = args[2].split(",");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        ExecutorService workers = Executors.newFixedThreadPool(64);
        ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger seq = new AtomicInteger(), ok = new AtomicInteger(), err = new AtomicInteger();
        tick.scheduleAtFixedRate(() -> workers.submit(() -> {
            int n = seq.getAndIncrement();
            String port = ports[n % ports.length];
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders?userId=" + (1000 + n % 50) + "&sku=88&qty=2"))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build();
            try {
                int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                (code == 200 ? ok : err).incrementAndGet();
            } catch (Exception e) {
                err.incrementAndGet();
            }
        }), 0, 1_000_000 / rate, TimeUnit.MICROSECONDS);
        for (int t = 10; t <= seconds; t += 10) {
            Thread.sleep(10_000);
            System.out.printf("%s load  last 10s: 200=%d 5xx=%d%n", LocalTime.now().withNano(0), ok.getAndSet(0), err.getAndSet(0));
        }
        tick.shutdownNow();
        workers.shutdown();
        workers.awaitTermination(15, TimeUnit.SECONDS);
    }
}
