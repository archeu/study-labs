package labs.jvm.jvm_gc_log_tuning;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * 交易靶场的下单接口，压成一个单进程：请求按固定速率到达（默认每秒 2000 个），
 * 每个请求解析报文、建订单、组装响应（都是马上就死的临时对象），并把订单放进“最近订单”缓存。
 * 延迟 = 完成时刻 - 计划到达时刻，所以 GC 停住的那段时间里排队的请求都会被算慢（不漏掉排队）。
 *
 * 参数（-D）：
 *   rate=2000      每秒请求数
 *   seconds=30     压多久
 *   cacheK=900     缓存里常驻多少千个订单（活对象的主体，约 0.15 KB/个）
 *   tempKB=48      每个请求的临时垃圾（KB）
 *   leak=false     true：每个请求再往一个只放不删的 static 列表里塞 4 KB（第 8 站的泄漏）
 */
public class OrderService {
    static final int RATE = Integer.getInteger("rate", 2000);
    static final int SECONDS = Integer.getInteger("seconds", 30);
    static final int CACHE = Integer.getInteger("cacheK", 900) * 1000;
    static final int TEMP_KB = Integer.getInteger("tempKB", 48);
    static final boolean LEAK = Boolean.getBoolean("leak");

    static final List<byte[]> LEAKED = new ArrayList<byte[]>();   // 只放不删
    static volatile Object sink;

    static final class Order {
        final long id, userId, skuId; final int qty; final long amountFen; final String status;
        Order(long id, long userId, long skuId, int qty, long amountFen, String status) {
            this.id = id; this.userId = userId; this.skuId = skuId; this.qty = qty; this.amountFen = amountFen; this.status = status;
        }
    }

    /** 最近订单缓存：LRU，满了就踢最老的，被踢掉的订单进了老年代才死 */
    static final Map<Long, Order> RECENT = new LinkedHashMap<Long, Order>(CACHE * 4 / 3 + 1, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Order> e) { return size() > CACHE; }
    };

    static long handle(long id, ThreadLocalRandom r) {
        // 1. 解析请求报文：字符串拼接、切分，全是临时对象
        String body = "{\"userId\":" + (1000 + r.nextInt(9000)) + ",\"skuId\":" + r.nextInt(500) + ",\"qty\":" + (1 + r.nextInt(3)) + "}";
        String[] parts = body.substring(1, body.length() - 1).split(",");
        long userId = Long.parseLong(parts[0].split(":")[1]);
        long skuId = Long.parseLong(parts[1].split(":")[1]);
        int qty = Integer.parseInt(parts[2].split(":")[1]);
        // 2. 建订单，放进缓存（活得久）
        Order o = new Order(id, userId, skuId, qty, 5980L * qty, "CREATED");
        RECENT.put(id, o);
        // 3. 组装响应、风控、日志……用一块临时缓冲区代表
        byte[] resp = new byte[TEMP_KB * 1024];
        resp[r.nextInt(resp.length)] = 1;
        sink = resp;
        if (LEAK) LEAKED.add(new byte[4096]);
        return o.amountFen;
    }

    public static void main(String[] args) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long t0 = System.nanoTime();
        for (long i = 0; i < CACHE; i++) RECENT.put(-1 - i, new Order(-1 - i, 1024, 88, 2, 11960, "PAID"));   // 预热：缓存装满
        System.out.printf("缓存预热 %d 个订单，用了 %d ms；堆上限 %d MB%n", CACHE, (System.nanoTime() - t0) / 1_000_000,
            Runtime.getRuntime().maxMemory() >> 20);
        LockSupport.parkNanos(1_000_000_000L);   // 空闲 1 秒，把预热和压测在 GC 日志里隔开
        System.out.printf("压测从 JVM 启动后第 %.1f 秒开始（GcLogStats 的第 4 个参数填它，跳过预热）%n",
            java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);

        int total = RATE * SECONDS;
        long interval = 1_000_000_000L / RATE;
        long[] lat = new long[total];
        long start = System.nanoTime();
        int n = 0;
        try {
            for (; n < total; n++) {
                long plan = start + n * interval;
                long now = System.nanoTime();
                if (plan > now) LockSupport.parkNanos(plan - now);
                handle(n, r);
                lat[n] = System.nanoTime() - plan;
            }
        } catch (OutOfMemoryError e) {
            double at = (System.nanoTime() - start) / 1e9;
            int leaked = LEAKED.size();
            LEAKED.clear();                                       // 先腾出内存，才打印得出来
            System.out.printf("OOM：压测第 %.1f 秒，泄漏列表里有 %d 块（%d MB）%n", at, leaked, leaked * 4 / 1024);
        }
        double secs = (System.nanoTime() - start) / 1e9;
        long[] a = Arrays.copyOf(lat, n);
        Arrays.sort(a);
        System.out.printf("处理 %d 个请求，用了 %.1f 秒（计划 %d 秒），实际 %.0f 个/秒%n", n, secs, SECONDS, n / secs);
        if (n > 0) System.out.printf("延迟：p50 %.2f ms，p99 %.2f ms，p99.9 %.2f ms，最长 %.1f ms%n",
            a[n / 2] / 1e6, a[(int) (n * 0.99)] / 1e6, a[(int) (n * 0.999)] / 1e6, a[n - 1] / 1e6);
    }
}
