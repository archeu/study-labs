package labs.network.rpc_serialization_discovery_lb;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 服务端：每条连接一个读线程（为了短，用 BIO；真实框架用 Netty 的 EventLoop），
 * 读出一帧 → 交给业务线程池 → 反射调用实现类 → 用同一个请求 id 写回响应。
 */
public final class RpcServer implements Closeable {
    private final ServerSocket serverSocket;
    private final Map<String, Object> services = new ConcurrentHashMap<>();
    private final ExecutorService bizPool;
    private volatile boolean running = true;

    public RpcServer(String name) throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        bizPool = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, name + "-biz"); t.setDaemon(true); return t;
        });
        Thread acceptor = new Thread(this::acceptLoop, name + "-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public String address() { return "127.0.0.1:" + serverSocket.getLocalPort(); }

    public <T> void export(Class<T> api, T impl) { services.put(api.getName(), impl); }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(() -> serve(s), "conn-" + s.getPort());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void serve(Socket s) {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            while (running) {
                Protocol.Frame req = Protocol.read(in);
                bizPool.execute(() -> handle(req, out));
            }
        } catch (IOException e) {
            // 连接关了
        }
    }

    private void handle(Protocol.Frame f, DataOutputStream out) {
        Serializers.Serializer ser = Protocol.serializer(f.serializer);
        RpcMessage.Response resp;
        try {
            RpcMessage.Request req = (RpcMessage.Request) ser.read(f.body);
            Object impl = services.get(req.service);
            if (impl == null) throw new IllegalStateException("没有服务 " + req.service);
            Method m = findMethod(impl.getClass(), req.method, req.args.length);
            resp = RpcMessage.Response.ok(m.invoke(impl, req.args));
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            resp = RpcMessage.Response.fail(c.toString());
        }
        try {
            byte[] body = ser.write(resp);
            synchronized (out) {   // 多个业务线程共用一条连接写回，必须一帧一帧整块写
                Protocol.write(out, new Protocol.Frame(Protocol.RESPONSE, f.serializer, f.requestId, body));
            }
        } catch (IOException ignored) {
        }
    }

    private static Method findMethod(Class<?> c, String name, int argc) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
        throw new IllegalStateException("没有方法 " + name);
    }

    @Override public void close() throws IOException {
        running = false;
        serverSocket.close();
        bizPool.shutdownNow();
    }
}
