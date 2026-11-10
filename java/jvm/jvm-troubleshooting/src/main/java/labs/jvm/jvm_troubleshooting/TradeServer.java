package labs.jvm.jvm_troubleshooting;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 交易靶场订单服务的缩小版，埋了三个雷：
 *   /order   每单都记进 RECENT，只放不删          → 内存泄漏，最后 OOM
 *   /export  把 N 行订单拼成一个大字符串再一次写出  → 每次几十 MB 大对象，频繁 Full GC
 *            /export?stream=true 是改好的流式写法
 *   /coupon  一分钱的优惠券按比例分摊，尾差分不出去  → 死循环，一个核打满
 *            /coupon?fixed=true 是改好的写法
 * 启动参数（-D）：port=8090，historyK=200 预置多少千条历史订单（常驻内存，导出从这里取），threads=8
 * /export?rows=200000 导出最近多少条
 */
public class TradeServer {
    static final AtomicLong NEXT_ID = new AtomicLong(9527);

    /** 雷 1：说是“防重复提交，记一下最近的订单”，却从来不删。 */
    static final Map<Long, Order> RECENT = new HashMap<>();

    /** 导出用的历史订单，常驻内存（正常的活对象，不是泄漏）。 */
    static final List<Order> HISTORY = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("port", 8090);
        int historyK = Integer.getInteger("historyK", 200);
        int threads = Integer.getInteger("threads", 8);
        for (int i = 0; i < historyK * 1000; i++) {
            HISTORY.add(new Order(i, 1000 + i % 5000, 1 + i % 300, 1 + i % 3, 5980 + i % 10000, false));
        }
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "http-exec-" + n.incrementAndGet());
            t.setDaemon(false);
            return t;
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        server.createContext("/order", safe(TradeServer::order));
        server.createContext("/export", safe(TradeServer::export));
        server.createContext("/coupon", safe(TradeServer::coupon));
        server.setExecutor(pool);
        server.start();
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        System.out.println("TradeServer pid=" + pid + " port=" + port + " history=" + HISTORY.size());
    }

    // ---------- 雷 1：内存泄漏 ----------
    static void order(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        long user = Long.parseLong(q.getOrDefault("user", "1024"));
        int sku = Integer.parseInt(q.getOrDefault("sku", "88"));
        int qty = Integer.parseInt(q.getOrDefault("qty", "2"));
        Order o = new Order(NEXT_ID.getAndIncrement(), user, sku, qty, 29900L * qty, true);
        synchronized (RECENT) {
            RECENT.put(o.id, o);           // 只放不删
        }
        reply(ex, 200, "order " + o.id + " ok\n");
    }

    // ---------- 雷 2：大对象 ----------
    static void export(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        boolean stream = "true".equals(q.get("stream"));
        int rows = Math.min(HISTORY.size(), Integer.parseInt(q.getOrDefault("rows", "200000")));
        List<Order> today = HISTORY.subList(HISTORY.size() - rows, HISTORY.size());
        if (!stream) {
            StringBuilder sb = new StringBuilder();          // 一路翻倍扩容
            for (Order o : today) o.appendCsv(sb);
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8); // 再复制两份
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        } else {
            ex.sendResponseHeaders(200, 0);                  // chunked，边算边写
            byte[] buf = new byte[64 * 1024];                // 一个请求只用这 64 KB
            int n = 0;
            StringBuilder line = new StringBuilder(64);
            try (OutputStream os = ex.getResponseBody()) {
                for (Order o : today) {
                    line.setLength(0);
                    o.appendCsv(line);
                    if (n + line.length() > buf.length) { os.write(buf, 0, n); n = 0; }
                    for (int i = 0; i < line.length(); i++) buf[n++] = (byte) line.charAt(i); // 全是 ASCII
                }
                os.write(buf, 0, n);
            }
        }
    }

    // ---------- 雷 3：死循环 ----------
    static void coupon(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        long couponCents = Long.parseLong(q.getOrDefault("cents", "1000"));
        String[] ps = q.getOrDefault("prices", "29900,29900").split(",");
        long[] prices = new long[ps.length];
        for (int i = 0; i < ps.length; i++) prices[i] = Long.parseLong(ps[i]);
        long[] share = "true".equals(q.get("fixed")) ? Coupon.splitFixed(couponCents, prices)
                                                     : Coupon.split(couponCents, prices);
        StringBuilder sb = new StringBuilder();
        for (long s : share) sb.append(s).append(' ');
        reply(ex, 200, sb.append('\n').toString());
    }

    // ---------- 工具 ----------
    interface H { void handle(HttpExchange ex) throws IOException; }

    /** 和框架一样：异常（包括 OOM）记一笔日志、回 500，进程接着跑。 */
    static HttpHandler safe(H h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (Throwable t) {
                System.err.println("[" + Thread.currentThread().getName() + "] " + ex.getRequestURI().getPath() + " failed:");
                t.printStackTrace();
                try { reply(ex, 500, t.toString() + "\n"); } catch (Throwable ignore) { }
            } finally {
                ex.close();
            }
        };
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    static void reply(HttpExchange ex, int code, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
