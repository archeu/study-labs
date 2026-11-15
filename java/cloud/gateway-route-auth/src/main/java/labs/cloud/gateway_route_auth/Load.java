package labs.cloud.gateway_route_auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** 压测小工具：java ... Load <url> <模式 block|async|none> <总数> <并发> */
public class Load {
    public static void main(String[] a) throws Exception {
        String url = a[0], mode = a[1];
        int n = Integer.parseInt(a[2]), conc = Integer.parseInt(a[3]);
        String token = Jwt.sign("1024", System.currentTimeMillis() / 1000 + 3600);
        HttpClient c = HttpClient.newHttpClient();
        Semaphore sem = new Semaphore(conc);
        List<CompletableFuture<Long>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            sem.acquire();
            long s = System.nanoTime();
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                    .header("X-Lab-Mode", mode).GET().build();
            fs.add(c.sendAsync(r, HttpResponse.BodyHandlers.discarding())
                    .handle((resp, err) -> { sem.release(); return (System.nanoTime() - s) / 1_000_000; }));
        }
        long[] lat = fs.stream().mapToLong(CompletableFuture::join).sorted().toArray();
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("模式 %-5s 并发 %d：%d 个请求 %.1f s，%.0f 个/秒，p50 %d ms，p99 %d ms%n",
                mode, conc, n, secs, n / secs, lat[lat.length / 2], lat[(int) (lat.length * 0.99) - 1]);
    }
}
