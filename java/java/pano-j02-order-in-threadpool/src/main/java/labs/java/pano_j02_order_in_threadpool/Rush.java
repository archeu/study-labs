package labs.java.pano_j02_order_in_threadpool;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 带 --rush 启动时：1000 个用户、50 路并发抢 100 件，五种写法各一轮。 */
@Component
class Rush implements ApplicationRunner {
    static final int STOCK = 100, USERS = 1000, CONCURRENCY = 50;

    private final StockService stock;
    private final ConfigurableApplicationContext ctx;

    Rush(StockService stock, ConfigurableApplicationContext ctx) {
        this.stock = stock;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("rush")) return;
        List<String> modes = args.getOptionValues("rush").isEmpty() || args.getOptionValues("rush").get(0).isEmpty()
                ? List.of("db", "mem", "naive", "sync", "cas", "db")   // 第一轮 db 只为预热，不打印
                : List.of(args.getOptionValues("rush").get(0).split(","));
        HttpClient http = HttpClient.newHttpClient();
        System.out.printf("%n库存 %d 件，%d 个用户，%d 路并发；JDK %s，%d 核%n", STOCK, USERS, CONCURRENCY,
                Runtime.version(), Runtime.getRuntime().availableProcessors());
        System.out.println("写法    卖出  库存剩  超卖  耗时ms  Tomcat线程  最多同时BLOCKED  CAS重试");
        boolean warm = modes.size() > 1 && modes.get(0).equals("db");
        for (int i = 0; i < modes.size(); i++) {
            Result r = round(http, modes.get(i));
            if (warm && i == 0) continue;
            System.out.printf("%-6s %5d %7d %5d %7d %10d %16d %8d%n", modes.get(i), r.sold, r.left,
                    Math.max(0, r.sold - STOCK),
                    r.ms, r.threads.size(), r.maxBlocked, stock.casRetries.sum());
        }
        System.out.println("线程名举例：" + lastThreads.stream().sorted().limit(3).toList());
        Thread.sleep(200);
        System.out.println("异步通知跑在：" + stock.asyncSeen.get());
        System.out.println("userOrders 里 " + stock.userOrders.size() + " 个用户");
        ctx.close();
    }

    private Set<String> lastThreads = Set.of();

    record Result(int sold, int left, long ms, Set<String> threads, int maxBlocked) {}

    private Result round(HttpClient http, String mode) throws Exception {
        stock.reset(STOCK);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger next = new AtomicInteger();
        AtomicInteger maxBlocked = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {         // 第 6 站：数一数卡在锁上的线程
            var mx = ManagementFactory.getThreadMXBean();
            while (running.get()) {
                int n = 0;
                for (ThreadInfo ti : mx.dumpAllThreads(false, false))
                    if (ti.getThreadName().contains("exec") && ti.getThreadState() == Thread.State.BLOCKED) n++;
                maxBlocked.accumulateAndGet(n, Math::max);
            }
        });
        long t0 = System.nanoTime();
        try (ExecutorService clients = Executors.newFixedThreadPool(CONCURRENCY)) {
            for (int c = 0; c < CONCURRENCY; c++) {
                clients.submit(() -> {
                    for (int u; (u = next.getAndIncrement()) < USERS; ) {
                        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:18080/seckill?mode=" + mode))
                                .header("X-User", String.valueOf(1000 + u))
                                .POST(HttpRequest.BodyPublishers.noBody()).build();
                        String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
                        threads.add(body.substring(body.indexOf(' ') + 1));
                    }
                    return null;
                });
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        running.set(false);
        sampler.join();
        lastThreads = threads;
        int left = mode.equals("mem") ? stock.memLeft() : stock.dbLeft();
        return new Result(stock.sold.get(), left, ms, threads, maxBlocked.get());
    }
}
