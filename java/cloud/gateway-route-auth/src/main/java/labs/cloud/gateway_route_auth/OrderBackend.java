package labs.cloud.gateway_route_auth;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingService;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;

/**
 * 网关后面的 order-svc 替身：JDK 自带 HttpServer + Nacos 客户端原生 API 注册。
 * 回显它收到的路径和 X-User-Id，看网关改写了什么；路径里带 slow 就睡 5 秒。
 * 用法：java ... OrderBackend 18091
 */
public class OrderBackend {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.contains("slow")) try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            String body = "port=" + port + " path=" + path + " X-User-Id=" + ex.getRequestHeaders().getFirst("X-User-Id");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.setExecutor(Executors.newFixedThreadPool(64));
        s.start();
        Properties p = new Properties();
        p.put(PropertyKeyConst.SERVER_ADDR, System.getProperty("nacos", "127.0.0.1:18848"));
        p.put(PropertyKeyConst.USERNAME, "nacos");
        p.put(PropertyKeyConst.PASSWORD, System.getProperty("nacos.pass", "LabPass123"));
        NamingService ns = NacosFactory.createNamingService(p);
        ns.registerInstance("order-svc", "127.0.0.1", port);
        System.out.println("order-svc backend on " + port + " registered");
    }
}
