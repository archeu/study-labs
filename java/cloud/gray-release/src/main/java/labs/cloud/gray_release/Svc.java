package labs.cloud.gray_release;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交易靶场里的一个服务实例（JDK 自带 HttpServer + Nacos 原生 API），注册时带元数据 hint=版本。
 *   java ... Svc inventory-svc v2 18112
 *   java ... Svc order-svc v1 18101 [propagate=true|false]
 * order-svc 收到请求后调 inventory-svc：propagate=true 时把 X-Gray 往下传，并只挑同版本的库存实例
 * （这就是 Feign 拦截器 + hint LoadBalancer 在做的事，这里手写出来看清楚）。
 * POST /admin/offline：先从 Nacos 下线自己（优雅下线第一步）。
 */
public class Svc {
    public static void main(String[] a) throws Exception {
        String name = a[0], ver = a[1];
        int port = Integer.parseInt(a[2]);
        boolean propagate = a.length < 4 || Boolean.parseBoolean(a[3].replace("propagate=", ""));
        Properties p = new Properties();
        p.put(PropertyKeyConst.SERVER_ADDR, System.getProperty("nacos", "127.0.0.1:18848"));
        p.put(PropertyKeyConst.USERNAME, "nacos");
        p.put(PropertyKeyConst.PASSWORD, System.getProperty("nacos.pass", "LabPass123"));
        NamingService ns = NacosFactory.createNamingService(p);
        AtomicInteger rr = new AtomicInteger();

        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        s.createContext("/", ex -> {
            String me = name.replace("-svc", "") + " " + ver + ":" + port;
            String body = me;
            int code = 200;
            if (name.equals("order-svc")) {
                String gray = ex.getRequestHeaders().getFirst("X-Gray");
                try {
                    List<Instance> all = ns.selectInstances("inventory-svc", true);   // Nacos 客户端缓存
                    List<Instance> pick = all;
                    if (propagate && gray != null) {
                        List<Instance> same = all.stream().filter(i -> gray.equals(i.getMetadata().get("hint"))).toList();
                        if (!same.isEmpty()) pick = same;                              // 和 LoadBalancer 一样：找不到就退回全部
                    }
                    Instance inv = pick.get(Math.floorMod(rr.getAndIncrement(), pick.size()));
                    HttpURLConnection c = (HttpURLConnection) URI.create("http://" + inv.getIp() + ":" + inv.getPort() + "/stock").toURL().openConnection();
                    if (propagate && gray != null) c.setRequestProperty("X-Gray", gray);
                    c.setConnectTimeout(200); c.setReadTimeout(800);
                    try (InputStream in = c.getInputStream()) { body = me + " → " + new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                } catch (Exception e) { body = me + " → 库存调用失败"; code = 502; }
            }
            if (ex.getRequestURI().getPath().equals("/admin/offline")) {
                try { ns.deregisterInstance(name, "127.0.0.1", port); body = "offline " + me; } catch (Exception e) { body = e.toString(); }
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.setExecutor(Executors.newFixedThreadPool(32));
        s.start();
        Instance me = new Instance();
        me.setIp("127.0.0.1"); me.setPort(port); me.setMetadata(Map.of("hint", ver, "version", ver));
        ns.registerInstance(name, me);
        System.out.println(name + " " + ver + " on " + port + " registered, propagate=" + propagate);
    }
}
