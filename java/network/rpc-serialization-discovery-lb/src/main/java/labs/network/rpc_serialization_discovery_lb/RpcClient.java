package labs.network.rpc_serialization_discovery_lb;

import java.io.*;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 客户端：把四件事串起来。
 *   Stub      —— JDK 动态代理，让 inventory.reserve(req) 看起来是本地调用
 *   序列化    —— Serializers
 *   协议      —— Protocol，一条长连接上靠请求 id 同时跑很多请求
 *   发现 + LB —— 订阅 Registry 拿地址列表，每次调用让 LoadBalancer 挑一个
 */
public final class RpcClient implements Closeable {
    private final Registry registry;
    private final LoadBalancers.LoadBalancer lb;
    private final Serializers.Serializer serializer;
    private final long timeoutMs;

    private final Map<String, List<String>> addresses = new ConcurrentHashMap<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();

    public RpcClient(Registry registry, LoadBalancers.LoadBalancer lb, Serializers.Serializer serializer, long timeoutMs) {
        this.registry = registry; this.lb = lb; this.serializer = serializer; this.timeoutMs = timeoutMs;
    }

    @SuppressWarnings("unchecked")
    public <T> T proxy(Class<T> api) {
        String service = api.getName();
        registry.subscribe(service, list -> addresses.put(service, list));   // 列表变了会被推过来
        return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (p, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);
            return call(service, method.getName(), args == null ? new Object[0] : args);
        });
    }

    private Object call(String service, String method, Object[] args) throws Exception {
        List<String> list = addresses.getOrDefault(service, Collections.<String>emptyList());
        if (list.isEmpty()) throw new IllegalStateException("No provider for " + service);
        String addr = lb.pick(list, active);
        AtomicInteger counter = active.computeIfAbsent(addr, k -> new AtomicInteger());
        counter.incrementAndGet();
        try {
            Connection c = connections.computeIfAbsent(addr, Connection::open);
            long id = nextId.incrementAndGet();
            byte[] body = serializer.write(new RpcMessage.Request(service, method, args));
            CompletableFuture<Protocol.Frame> f = c.send(new Protocol.Frame(Protocol.REQUEST, serializer.id(), id, body));
            Protocol.Frame respFrame;
            try {
                respFrame = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                c.pending.remove(id);          // 不再等了；但对面可能已经在做、甚至做完了
                throw new TimeoutException(method + " 超过 " + timeoutMs + " ms 没回复，地址 " + addr);
            }
            RpcMessage.Response resp = (RpcMessage.Response) serializer.read(respFrame.body);
            if (resp.error != null) throw new RuntimeException("远端异常：" + resp.error);
            return resp.value;
        } finally {
            counter.decrementAndGet();
        }
    }

    /** 一条 TCP 长连接 + 一个读线程。pending：请求 id → 正在等它的 Future。 */
    static final class Connection {
        final Socket socket;
        final DataOutputStream out;
        final Map<Long, CompletableFuture<Protocol.Frame>> pending = new ConcurrentHashMap<>();

        private Connection(Socket s) throws IOException {
            socket = s;
            s.setTcpNoDelay(true);
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Thread reader = new Thread(() -> {
                try {
                    while (true) {
                        Protocol.Frame f = Protocol.read(in);
                        CompletableFuture<Protocol.Frame> waiter = pending.remove(f.requestId);
                        if (waiter != null) waiter.complete(f);   // 超时后才回来的响应，没人等，丢掉
                    }
                } catch (IOException e) {
                    for (CompletableFuture<Protocol.Frame> w : pending.values()) w.completeExceptionally(e);
                }
            }, "rpc-reader-" + s.getPort());
            reader.setDaemon(true);
            reader.start();
        }

        static Connection open(String addr) {
            String[] hp = addr.split(":");
            try { return new Connection(new Socket(hp[0], Integer.parseInt(hp[1]))); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }

        CompletableFuture<Protocol.Frame> send(Protocol.Frame f) throws IOException {
            CompletableFuture<Protocol.Frame> fut = new CompletableFuture<>();
            pending.put(f.requestId, fut);
            synchronized (out) { Protocol.write(out, f); }
            return fut;
        }
    }

    @Override public void close() throws IOException {
        for (Connection c : connections.values()) c.socket.close();
    }
}
