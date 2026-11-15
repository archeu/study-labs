package labs.perf.full_link_load_test;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 实验 1：开环阶梯加压，找一台“券服务”的容量拐点。
 * 服务：JDK 自带 HttpServer，8 个工作线程；每个请求 0.3 ms CPU + 等 4 ms（模拟一次 Redis Lua 往返加业务）。
 * 理论上限 ≈ 8 线程 ÷ 4.3 ms ≈ 1860 QPS（线程先满，CPU 还没满）。
 * 压测端：按固定速率发请求（开环），延迟从“该发出的时刻”算起，不会把排队藏起来。
 * 注意：压测端和服务在同一台 2 核机器上，数字只用来看形状。用到 java.net.http，需要 JDK 11+ 运行。
 */
public class CapacityLab {

    public static void main(String[] args) throws Exception {
        int port = 18099, workers = 8;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 4096);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        server.setExecutor(pool);
        server.createContext("/grab", ex -> {
            long end = System.nanoTime() + 300_000;           // 0.3 ms CPU
            long x = 0; while (System.nanoTime() < end) x++;
            try { Thread.sleep(4); } catch (InterruptedException ignored) { }   // 4 ms 等 Redis
            byte[] body = ("ok" + (x & 1)).getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();

        HttpClient client = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(4))
                .connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://127.0.0.1:" + port + "/grab");
        // 预热
        step(client, uri, 300, 3, false);
        System.out.println("目标QPS  实际QPS   P50(ms)  P99(ms)  超过50ms");
        for (int rate : new int[]{400, 800, 1200, 1500, 1700, 1900, 2200}) {
            step(client, uri, rate, 5, true);
        }
        server.stop(0);
        pool.shutdownNow();
        System.exit(0);
    }

    /** 以 rate/秒的固定速率发 secs 秒，统计完成数和延迟（从计划发出的时刻算）。 */
    static void step(HttpClient c, URI uri, int rate, int secs, boolean print) throws Exception {
        int total = rate * secs;
        long intervalNs = 1_000_000_000L / rate;
        List<CompletableFuture<Long>> fs = new ArrayList<>(total);
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        long t0 = System.nanoTime();
        for (int i = 0; i < total; i++) {
            long planned = t0 + i * intervalNs;
            long wait = planned - System.nanoTime();
            if (wait > 0) { long ms = wait / 1_000_000; int ns = (int) (wait % 1_000_000); Thread.sleep(ms, ns); }
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            fs.add(c.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .handle((r, e) -> e == null ? (System.nanoTime() - planned) : -1L));
        }
        long[] lat = new long[total]; int ok = 0;
        long lastDone = 0;
        for (CompletableFuture<Long> f : fs) { long v = f.get(); if (v >= 0) lat[ok++] = v; }
        long elapsed = System.nanoTime() - t0;
        ticker.shutdown();
        if (!print) return;
        long[] l = Arrays.copyOf(lat, ok); Arrays.sort(l);
        long over = Arrays.stream(l).filter(v -> v > 50_000_000L).count();
        System.out.printf("%6d  %7.0f  %8.1f  %8.1f  %6.1f%%%n", rate, ok / (elapsed / 1e9),
                l[(int) (ok * 0.50)] / 1e6, l[(int) (ok * 0.99)] / 1e6, 100.0 * over / ok);
    }
}
