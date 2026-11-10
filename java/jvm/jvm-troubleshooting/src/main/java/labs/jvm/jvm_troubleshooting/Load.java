package labs.jvm.jvm_troubleshooting;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测客户端：java Load <url> <并发> <秒数>
 * 每个线程循环发请求，打印每 5 秒的请求数、500 个数，最后打印延迟 p50 / p99 / 最大。
 */
public class Load {
    public static void main(String[] args) throws Exception {
        final String url = args[0];
        int threads = Integer.parseInt(args[1]);
        final int seconds = Integer.parseInt(args[2]);
        final AtomicLong ok = new AtomicLong(), err = new AtomicLong();
        final long[][] lat = new long[threads][];
        final int[] cnt = new int[threads];
        final long end = System.nanoTime() + seconds * 1_000_000_000L;
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            lat[id] = new long[1 << 16];
            ts[t] = new Thread(() -> {
                byte[] buf = new byte[64 * 1024];
                while (System.nanoTime() < end) {
                    long s = System.nanoTime();
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(5000);
                        c.setReadTimeout(30000);
                        int code = c.getResponseCode();
                        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        if (in != null) { while (in.read(buf) > 0) { } in.close(); }
                        if (code == 200) ok.incrementAndGet(); else err.incrementAndGet();
                    } catch (Exception e) {
                        err.incrementAndGet();
                    }
                    long us = (System.nanoTime() - s) / 1000;
                    if (cnt[id] == lat[id].length) lat[id] = Arrays.copyOf(lat[id], cnt[id] * 2);
                    lat[id][cnt[id]++] = us;
                }
            }, "load-" + t);
            ts[t].start();
        }
        long start = System.nanoTime();
        long lastOk = 0, lastErr = 0;
        while (System.nanoTime() < end) {
            Thread.sleep(5000);
            long o = ok.get(), e = err.get();
            System.out.printf("t=%3ds  ok=%d  500/err=%d%n", (System.nanoTime() - start) / 1_000_000_000L, o - lastOk, e - lastErr);
            lastOk = o; lastErr = e;
        }
        for (Thread t : ts) t.join();
        int total = 0;
        for (int c : cnt) total += c;
        long[] all = new long[total];
        int k = 0;
        for (int t = 0; t < threads; t++) { System.arraycopy(lat[t], 0, all, k, cnt[t]); k += cnt[t]; }
        Arrays.sort(all);
        if (total > 0) System.out.printf("requests=%d ok=%d err=%d  p50=%.1fms p99=%.1fms max=%.1fms%n",
                total, ok.get(), err.get(), all[total / 2] / 1000.0, all[(int) (total * 0.99)] / 1000.0, all[total - 1] / 1000.0);
    }
}
