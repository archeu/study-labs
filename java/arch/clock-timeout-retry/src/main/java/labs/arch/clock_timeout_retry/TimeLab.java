package labs.arch.clock_timeout_retry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 交易靶场 J09 全景“一次下单跨三个库”的第 5、8、10 站：订单服务调库存服务冻结 2 件 sku 88。
 * 库存服务是同一个 JVM 里起的一个 HTTP 服务（com.sun.net.httpserver），订单服务用 HttpURLConnection 调它。
 *
 *   timeout  读超时 300 ms，库存服务 400 ms 才冻结完：订单服务报超时，库存却冻了
 *   retry    像 Dubbo 默认 retries=2 那样超时就再调：不带幂等键 / 带幂等键
 *   storm    库存服务重启 1 秒，200 单同时来：立即重试 / 指数退避 / 指数退避 + 全抖动
 *   clock    （模拟）NTP 把墙上时钟往前拨 61 秒：用 currentTimeMillis 和 nanoTime 判 60 秒超时
 *
 * 参数：all | timeout | retry | storm | clock
 */
public class TimeLab {

    static final long T0 = System.nanoTime();

    static long ms() { return (System.nanoTime() - T0) / 1_000_000; }

    // ---------------- 库存服务 ----------------

    static class InventorySvc {
        final HttpServer server;
        final int port;
        final AtomicInteger available = new AtomicInteger(50);
        final AtomicInteger frozen = new AtomicInteger(0);
        final Map<String, Boolean> seenKeys = new ConcurrentHashMap<String, Boolean>();
        volatile long delayMs = 0;
        volatile boolean log = true;
        long callT0;

        // storm 用：重启窗口、并发上限、每 100 ms 一格的到达数
        volatile long downUntilNanos = 0;
        Semaphore capacity = null;
        long busyMs = 0;
        AtomicIntegerArray arrivals = new AtomicIntegerArray(80);
        long stormT0;

        InventorySvc() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
            server.setExecutor(Executors.newFixedThreadPool(64));
            server.createContext("/reserve", this::reserve);
            server.createContext("/stock", ex -> reply(ex, 200, available.get() + "/" + frozen.get()));
            server.start();
            port = server.getAddress().getPort();
        }

        void reset() { available.set(50); frozen.set(0); seenKeys.clear(); }

        void reserve(HttpExchange ex) throws IOException {
            Map<String, String> q = query(ex);
            if (capacity != null) { storm(ex); return; }
            String key = q.get("key");
            if (key != null && seenKeys.putIfAbsent(key, Boolean.TRUE) != null) {
                if (log) System.out.printf("    t=%4d ms  库存服务：幂等键 %s 已经见过，不再冻结，返回 DUPLICATE%n", ms() - callT0, key);
                reply(ex, 200, "DUPLICATE");
                return;
            }
            sleep(delayMs);
            int n = Integer.parseInt(q.get("n"));
            available.addAndGet(-n);
            frozen.addAndGet(n);
            if (log) System.out.printf("    t=%4d ms  库存服务：冻结 %d 件，可售/冻结 = %d/%d%n",
                    ms() - callT0, n, available.get(), frozen.get());
            reply(ex, 200, "OK");
        }

        void storm(HttpExchange ex) throws IOException {
            long now = System.nanoTime();
            int slot = (int) ((now - stormT0) / 100_000_000L);
            if (slot < arrivals.length()) arrivals.incrementAndGet(slot);
            if (now < downUntilNanos) { reply(ex, 503, "RESTARTING"); return; }
            if (!capacity.tryAcquire()) { reply(ex, 503, "BUSY"); return; }
            try { sleep(busyMs); reply(ex, 200, "OK"); } finally { capacity.release(); }
        }

        void stop() { server.stop(0); ((ExecutorService) server.getExecutor()).shutdownNow(); }
    }

    // ---------------- 订单服务这一侧的调用 ----------------

    static String call(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(1000);
        c.setReadTimeout(readTimeoutMs);
        c.setDoOutput(true);
        c.getOutputStream().close();
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(in);
        if (code >= 500) throw new IOException(code + " " + body);
        return body;
    }

    // ---------------- 四段实验 ----------------

    static void timeout(InventorySvc inv) throws Exception {
        System.out.println("[timeout] 订单服务调 Reserve(9527, sku 88, 2)，读超时 300 ms；库存服务这次 400 ms 才处理完");
        inv.reset();
        inv.delayMs = 400;
        inv.callT0 = ms();
        try {
            call("http://127.0.0.1:" + inv.port + "/reserve?order=9527&sku=88&n=2", 300);
        } catch (SocketTimeoutException e) {
            System.out.printf("    t=%4d ms  订单服务：%s → 当成“失败”%n", ms() - inv.callT0, e.getClass().getSimpleName());
        }
        Thread.sleep(300);
        System.out.printf("    t=%4d ms  查库存：可售/冻结 = %s  ← 超时只说明“不知道”，对方其实做完了%n%n",
                ms() - inv.callT0, stock(inv));
    }

    static void retry(InventorySvc inv) throws Exception {
        for (boolean withKey : new boolean[]{false, true}) {
            System.out.println(withKey
                    ? "[retry] 同样重试 2 次，这次带幂等键 key=9527-inventory（库存服务先查这个键见没见过）"
                    : "[retry] 像 Dubbo 默认 retries=2：超时就再调，最多调 3 次；不带幂等键");
            inv.reset();
            inv.delayMs = 400;
            inv.callT0 = ms();
            String url = "http://127.0.0.1:" + inv.port + "/reserve?order=9527&sku=88&n=2"
                    + (withKey ? "&key=9527-inventory" : "");
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    String r = call(url, 300);
                    System.out.printf("    t=%4d ms  订单服务：第 %d 次调用返回 %s%n", ms() - inv.callT0, attempt, r);
                    break;
                } catch (SocketTimeoutException e) {
                    System.out.printf("    t=%4d ms  订单服务：第 %d 次调用超时%s%n", ms() - inv.callT0, attempt,
                            attempt < 3 ? "，马上重试" : "，放弃，回滚");
                }
            }
            Thread.sleep(500);
            System.out.printf("    最后查库存：可售/冻结 = %s%n%n", stock(inv));
        }
    }

    static void storm(InventorySvc inv) throws Exception {
        System.out.println("[storm] 库存服务重启 1 秒（这 1 秒全回 503），200 单同时来；恢复后最多同时处理 10 个、每个 50 ms");
        System.out.println("        每单最多试 6 次。到达数每 100 ms 一格，看前 4 秒");
        inv.log = false;
        String[] names = {"立即重试", "指数退避 100ms×2^k", "指数退避 + 全抖动"};
        for (int p = 0; p < 3; p++) {
            inv.capacity = new Semaphore(10);
            inv.busyMs = 50;
            inv.arrivals = new AtomicIntegerArray(80);
            inv.stormT0 = System.nanoTime();
            inv.downUntilNanos = inv.stormT0 + 1_000_000_000L;
            final int policy = p;
            final AtomicInteger ok = new AtomicInteger(), gaveUp = new AtomicInteger();
            final AtomicLong lastOk = new AtomicLong();
            final CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(200);
            final String url = "http://127.0.0.1:" + inv.port + "/reserve?n=1";
            final long t0 = inv.stormT0;
            ExecutorService clients = Executors.newFixedThreadPool(200);
            for (int i = 0; i < 200; i++) {
                clients.execute(() -> {
                    try {
                        start.await();
                        for (int k = 0; k < 6; k++) {
                            try {
                                call(url, 2000);
                                ok.incrementAndGet();
                                lastOk.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
                                return;
                            } catch (IOException e) {
                                if (k == 5) break;
                                long cap = 100L << k;              // 100, 200, 400, 800, 1600
                                long wait = policy == 0 ? 0 : policy == 1 ? cap : ThreadLocalRandom.current().nextLong(cap + 1);
                                Thread.sleep(wait);
                            }
                        }
                        gaveUp.incrementAndGet();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
            clients.shutdown();
            int total = 0, peak = 0, peakSlot = 0;
            StringBuilder bars = new StringBuilder();
            for (int s = 0; s < inv.arrivals.length(); s++) {
                int a = inv.arrivals.get(s);
                total += a;
                if (s >= 10 && a > peak) { peak = a; peakSlot = s; }
                if (s < 40) bars.append(bar(a));
            }
            System.out.printf("  %-18s 成功 %3d 单，放弃 %3d 单，共发 %4d 次请求（%.1f 倍）；恢复后峰值 %3d 次/100ms（第 %d.%d 秒）；最后一单成功在 %d ms%n",
                    names[p], ok.get(), gaveUp.get(), total, total / 200.0, peak, peakSlot / 10, peakSlot % 10, lastOk.get());
            System.out.println("    0s|" + bars.substring(0, 10) + "|1s 恢复|" + bars.substring(10, 20) + "|2s|"
                    + bars.substring(20, 30) + "|3s|" + bars.substring(30, 40) + "|4s");
        }
        System.out.println("    （每格一个字符：· 0  ▁ 1-9  ▃ 10-49  ▅ 50-199  █ ≥200）");
        System.out.println();
        inv.capacity = null;
        inv.log = true;
    }

    static void clock() {
        System.out.println("[clock]（模拟）TC 判断“开始后 60 秒还没结束 → 超时回滚”；事务第 5 秒时，NTP 把墙上时钟往前拨了 61 秒");
        System.out.println("        用 Clock.offset 模拟这次跳变，没有改系统时间");
        Clock wall = Clock.systemUTC();
        long beginWall = wall.millis();
        long beginMono = System.nanoTime();
        // 5 秒后：墙上时钟 = 真实时间 + 61 秒；单调时钟照常走 5 秒
        Clock jumped = Clock.offset(wall, Duration.ofSeconds(61 + 5));
        long monoNow = beginMono + 5_000_000_000L;
        long byWall = jumped.millis() - beginWall;
        long byMono = (monoNow - beginMono) / 1_000_000;
        System.out.printf("    用 currentTimeMillis 算：已经过了 %5d ms → %s%n", byWall, byWall > 60_000 ? "超时，回滚（误杀）" : "未超时");
        System.out.printf("    用 nanoTime 算：         已经过了 %5d ms → %s%n%n", byMono, byMono > 60_000 ? "超时，回滚" : "未超时，继续等");
    }

    // ---------------- 工具 ----------------

    static String stock(InventorySvc inv) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + inv.port + "/stock").openConnection();
        return readAll(c.getInputStream());
    }

    static char bar(int a) { return a == 0 ? '·' : a < 10 ? '▁' : a < 50 ? '▃' : a < 200 ? '▅' : '█'; }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<String, String>();
        String q = ex.getRequestURI().getQuery();
        if (q != null) for (String kv : q.split("&")) { String[] p = kv.split("=", 2); m.put(p[0], p.length > 1 ? p[1] : ""); }
        return m;
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        try {
            ex.sendResponseHeaders(code, b.length);
            OutputStream os = ex.getResponseBody();
            os.write(b);
            os.close();
        } catch (IOException clientGone) {
            // 客户端已经超时断开：服务端的活照样干完了，只是回包没人收
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        in.close();
        return sb.toString();
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "all";
        InventorySvc inv = new InventorySvc();
        try {
            if (what.equals("all") || what.equals("timeout")) timeout(inv);
            if (what.equals("all") || what.equals("retry")) retry(inv);
            if (what.equals("all") || what.equals("storm")) storm(inv);
            if (what.equals("all") || what.equals("clock")) clock();
        } finally {
            inv.stop();
        }
    }
}
