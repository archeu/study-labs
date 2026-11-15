package labs.cloud.gray_release;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * run.sh 的小工具：
 *   publish <dataId> <内容>                 发布 Nacos 配置
 *   hit <user> <次数>                         以某个用户身份经网关下单 N 次，统计落到哪些版本
 *   load <user> <秒数> <每秒次数>             持续下单，按秒统计成功和失败
 */
public class Tool {
    static final HttpClient C = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "publish" -> {
                java.util.Properties p = props();
                ConfigService cs = NacosFactory.createConfigService(p);
                System.out.println(System.currentTimeMillis() + " publish " + a[1] + " -> "
                        + cs.publishConfig(a[1], "DEFAULT_GROUP", a[2].replace("\\n", "\n"), "yaml"));
                cs.shutDown();
            }
            case "hit" -> {
                Map<String, Integer> m = new TreeMap<>();
                for (int i = 0; i < Integer.parseInt(a[2]); i++) m.merge(call(a[1]), 1, Integer::sum);
                m.forEach((k, v) -> System.out.println("    " + v + " 次  " + k));
            }
            case "load" -> {
                int secs = Integer.parseInt(a[2]), rate = Integer.parseInt(a[3]);
                long t0 = System.currentTimeMillis();
                int totalFail = 0;
                for (int s = 0; s < secs; s++) {
                    int ok = 0, fail = 0;
                    for (int i = 0; i < rate; i++) {
                        long due = t0 + s * 1000L + i * 1000L / rate;
                        long w = due - System.currentTimeMillis();
                        if (w > 0) Thread.sleep(w);
                        if (call(a[1]).startsWith("ERR")) fail++; else ok++;
                    }
                    totalFail += fail;
                    if (fail > 0 || s % 5 == 0) System.out.printf("    %02d s  成功 %d  失败 %d%n", s, ok, fail);
                }
                System.out.println("    合计失败 " + totalFail);
            }
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    static String call(String user) {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18070/api/orders/9527"))
                    .header("X-User-Id", user).timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = C.send(r, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : "ERR " + resp.statusCode();
        } catch (Exception e) { return "ERR " + e.getClass().getSimpleName(); }
    }

    static java.util.Properties props() {
        java.util.Properties p = new java.util.Properties();
        p.put(PropertyKeyConst.SERVER_ADDR, System.getProperty("nacos", "127.0.0.1:18848"));
        p.put(PropertyKeyConst.USERNAME, "nacos");
        p.put(PropertyKeyConst.PASSWORD, "LabPass123");
        return p;
    }
}
