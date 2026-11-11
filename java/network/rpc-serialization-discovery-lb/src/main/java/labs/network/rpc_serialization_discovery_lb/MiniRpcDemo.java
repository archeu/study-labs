package labs.network.rpc_serialization_discovery_lb;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * 200 行的迷你 RPC 跑一遍：订单服务调库存服务 reserve(9527, 88, 2)。
 * 依次看：序列化有多大、一帧长什么样、轮询怎么分、注册中心增删实例、慢实例下三种负载均衡、超时以后对面还在扣。
 */
public final class MiniRpcDemo {

    /** 库存服务实现：delayMs 模拟查库慢，deducted 记下真正扣了几次。 */
    static final class InventoryImpl implements Api.InventoryService {
        final String name;
        volatile long delayMs;
        volatile boolean broken;            // 模拟“库存库连不上”：立刻抛异常
        final AtomicInteger deducted = new AtomicInteger();
        InventoryImpl(String name, long delayMs) { this.name = name; this.delayMs = delayMs; }
        public Api.ReserveReply reserve(Api.ReserveRequest req) {
            if (broken) throw new IllegalStateException("库存库连接池耗尽");
            sleep(delayMs);
            deducted.incrementAndGet();
            return new Api.ReserveReply(true, name);
        }
    }

    static final class Instance {
        final InventoryImpl impl; final RpcServer server;
        Instance(String name, long delayMs) throws IOException {
            impl = new InventoryImpl(name, delayMs);
            server = new RpcServer(name);
            server.export(Api.InventoryService.class, impl);
        }
    }

    static final String SVC = Api.InventoryService.class.getName();
    static final Api.ReserveRequest REQ = new Api.ReserveRequest(9527, 88, 2);

    public static void main(String[] args) throws Exception {
        System.out.println("JDK " + System.getProperty("java.version"));
        serializationSizes();
        Registry registry = new Registry();
        Instance a = new Instance("A", 0), b = new Instance("B", 0);
        registry.register(SVC, a.server.address());
        registry.register(SVC, b.server.address());

        try (RpcClient client = new RpcClient(registry, LoadBalancers.roundRobin(), Serializers.COMPACT, 1000)) {
            Api.InventoryService inventory = client.proxy(Api.InventoryService.class);
            System.out.println("\n== 2. 一次调用，看起来像本地方法 ==");
            System.out.println("inventory 的真实类型：" + inventory.getClass().getName());
            System.out.println("reserve(9527, 88, 2) → " + inventory.reserve(REQ));

            System.out.println("\n== 3. 轮询：1000 次顺序调用 ==");
            System.out.println(countByInstance(inventory, 1000));

            System.out.println("\n== 4. 服务发现：C 上线，A 下线 ==");
            Instance c = new Instance("C", 0);
            registry.register(SVC, c.server.address());
            System.out.println("C 注册后 300 次：" + countByInstance(inventory, 300));
            registry.unregister(SVC, a.server.address());
            a.server.close();
            System.out.println("A 摘除并关闭后 300 次：" + countByInstance(inventory, 300));
            c.server.close();
            registry.unregister(SVC, c.server.address());
        }

        slowInstanceExperiment();
        brokenInstanceExperiment();
        timeoutExperiment();
        b.server.close();
        System.exit(0);
    }

    static void serializationSizes() throws IOException {
        System.out.println("\n== 1. 序列化：同一个 ReserveRequest(9527, 88, 2) 变成多少字节 ==");
        byte[] jdk = Serializers.JDK.write(REQ), compact = Serializers.COMPACT.write(REQ);
        String json = "{\"orderId\":9527,\"skuId\":88,\"qty\":2}";
        System.out.printf("JDK 序列化 %d 字节 | JSON %d 字节 | Compact %d 字节：%s%n",
                jdk.length, json.getBytes("UTF-8").length, compact.length, hex(compact));
        System.out.println("JDK 字节流里能读到的字符串：" + printable(jdk));
        RpcMessage.Request whole = new RpcMessage.Request(SVC, "reserve", new Object[]{REQ});
        byte[] wj = Serializers.JDK.write(whole), wc = Serializers.COMPACT.write(whole);
        System.out.printf("整条 RPC 请求（服务名 + 方法名 + 参数）：JDK %d 字节，Compact %d 字节%n", wj.length, wc.length);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Protocol.write(new DataOutputStream(bos), new Protocol.Frame(Protocol.REQUEST, (byte) 2, 1, wc));
        byte[] frame = bos.toByteArray();
        System.out.println("一帧 = 17 字节头 + " + wc.length + " 字节体 = " + frame.length + " 字节");
        System.out.println("头：" + hex(Arrays.copyOf(frame, Protocol.HEADER_LENGTH))
                + "  ← 魔数 cafe | 版本 01 | 类型 00=请求 | 序列化 02 | 请求 id 8 字节 | 体长度 4 字节");
    }

    static Map<String, Integer> countByInstance(Api.InventoryService inv, int n) {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < n; i++) m.merge(inv.reserve(REQ).instance, 1, Integer::sum);
        return m;
    }

    /** 两台实例：fast 每次 2 ms，slow 每次 30 ms。16 个线程一共调 2400 次，比较三种负载均衡。 */
    static void slowInstanceExperiment() throws Exception {
        System.out.println("\n== 5. 一台变慢：fast 2 ms，slow 30 ms，16 线程共 2400 次 ==");
        String[] names = {"轮询", "随机", "最少活跃"};
        LoadBalancers.LoadBalancer[] lbs = {LoadBalancers.roundRobin(), LoadBalancers.random(), LoadBalancers.leastActive()};
        for (int k = 0; k < lbs.length; k++) {
            Instance fast = new Instance("fast", 2), slow = new Instance("slow", 30);
            Registry r = new Registry();
            r.register(SVC, fast.server.address());
            r.register(SVC, slow.server.address());
            try (RpcClient client = new RpcClient(r, lbs[k], Serializers.COMPACT, 5000)) {
                Api.InventoryService inv = client.proxy(Api.InventoryService.class);
                inv.reserve(REQ); inv.reserve(REQ);            // 建好连接
                fast.impl.deducted.set(0); slow.impl.deducted.set(0);
                ExecutorService pool = Executors.newFixedThreadPool(16);
                List<Long> lat = Collections.synchronizedList(new ArrayList<Long>());
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < 16; t++) fs.add(pool.submit(() -> {
                    for (int i = 0; i < 150; i++) {
                        long s = System.nanoTime();
                        inv.reserve(REQ);
                        lat.add(System.nanoTime() - s);
                    }
                }));
                for (Future<?> f : fs) f.get();
                pool.shutdown();
                Collections.sort(lat);
                double mean = 0; for (long x : lat) mean += x; mean /= lat.size();
                System.out.printf("%-4s fast %4d 次 / slow %4d 次 | 平均 %5.1f ms | p90 %5.1f ms | p99 %5.1f ms%n",
                        names[k], fast.impl.deducted.get(), slow.impl.deducted.get(),
                        mean / 1e6, lat.get(lat.size() * 90 / 100) / 1e6, lat.get(lat.size() * 99 / 100) / 1e6);
            }
            fast.server.close(); slow.server.close();
        }
    }

    /** 三台实例各 2 ms，其中 bad 坏了：不干活，立刻返回错误。16 线程共 2400 次。 */
    static void brokenInstanceExperiment() throws Exception {
        System.out.println("\n== 5b. 一台坏了但还在注册中心里：它立刻报错，16 线程共 2400 次 ==");
        String[] names = {"轮询", "最少活跃"};
        LoadBalancers.LoadBalancer[] lbs = {LoadBalancers.roundRobin(), LoadBalancers.leastActive()};
        for (int k = 0; k < lbs.length; k++) {
            Instance a = new Instance("a", 2), b = new Instance("b", 2), bad = new Instance("bad", 2);
            bad.impl.broken = true;
            Registry r = new Registry();
            for (Instance x : new Instance[]{a, b, bad}) r.register(SVC, x.server.address());
            try (RpcClient client = new RpcClient(r, lbs[k], Serializers.COMPACT, 5000)) {
                Api.InventoryService inv = client.proxy(Api.InventoryService.class);
                for (int i = 0; i < 3; i++) try { inv.reserve(REQ); } catch (RuntimeException ignored) { }
                a.impl.deducted.set(0); b.impl.deducted.set(0);
                AtomicInteger errors = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(16);
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < 16; t++) fs.add(pool.submit(() -> {
                    for (int i = 0; i < 150; i++) {
                        try { inv.reserve(REQ); } catch (RuntimeException e) { errors.incrementAndGet(); }
                    }
                }));
                for (Future<?> f : fs) f.get();
                pool.shutdown();
                System.out.printf("%-4s a %4d 次 / b %4d 次 / bad %4d 次 | 错误率 %4.1f%%%n", names[k],
                        a.impl.deducted.get(), b.impl.deducted.get(), errors.get(), errors.get() * 100.0 / 2400);
            }
            a.server.close(); b.server.close(); bad.server.close();
        }
    }

    /** 客户端 100 ms 超时，库存那边要 300 ms。 */
    static void timeoutExperiment() throws Exception {
        System.out.println("\n== 6. 超时：客户端等 100 ms，库存要 300 ms ==");
        Instance slow = new Instance("slow", 300);
        Registry r = new Registry();
        r.register(SVC, slow.server.address());
        try (RpcClient client = new RpcClient(r, LoadBalancers.roundRobin(), Serializers.COMPACT, 100)) {
            Api.InventoryService inv = client.proxy(Api.InventoryService.class);
            long s = System.nanoTime();
            try {
                inv.reserve(REQ);
            } catch (UndeclaredThrowableException e) {
                System.out.printf("%d ms 后客户端收到：%s%n", (System.nanoTime() - s) / 1_000_000, e.getCause());
            }
            System.out.println("此刻库存已扣次数：" + slow.impl.deducted.get());
            sleep(400);
            System.out.println("再等 400 ms，库存已扣次数：" + slow.impl.deducted.get() + "  ← 超时不等于没做");
        }
        slow.server.close();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x));
        return sb.toString().trim();
    }

    static String printable(byte[] b) {
        StringBuilder sb = new StringBuilder(), run = new StringBuilder();
        for (byte x : b) {
            if (x >= 0x20 && x < 0x7f) run.append((char) x);
            else { if (run.length() >= 3) sb.append(run).append(" | "); run.setLength(0); }
        }
        if (run.length() >= 3) sb.append(run);
        return sb.toString();
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
