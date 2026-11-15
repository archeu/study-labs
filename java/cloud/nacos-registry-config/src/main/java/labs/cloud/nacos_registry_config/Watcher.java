package labs.cloud.nacos_registry_config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 站在“调用方”的位置看 Nacos：订阅 inventory-svc 的实例列表、监听 inventory-svc.yaml。
 * 每收到一次推送打印一行，带毫秒时间戳，run.sh 用它掐表。
 *
 *   watch                         订阅并一直打印
 *   publish <dataId> <内容>       发布一份配置（内容里的 \n 换成真换行）
 */
public class Watcher {
    static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.put(PropertyKeyConst.SERVER_ADDR, System.getProperty("nacos", "127.0.0.1:18848"));
        // Nacos 3 官方镜像默认开鉴权，客户端要带账号密码
        p.put(PropertyKeyConst.USERNAME, System.getProperty("nacos.user", "nacos"));
        p.put(PropertyKeyConst.PASSWORD, System.getProperty("nacos.pass", "LabPass123"));
        String cmd = args.length > 0 ? args[0] : "watch";
        if (cmd.equals("publish")) {
            ConfigService cs = NacosFactory.createConfigService(p);
            boolean ok = cs.publishConfig(args[1], "DEFAULT_GROUP", args[2].replace("\\n", "\n"), "yaml");
            System.out.println(now() + " publish " + args[1] + " -> " + ok);
            cs.shutDown();
            return;
        }
        NamingService ns = NacosFactory.createNamingService(p);
        ns.subscribe("inventory-svc", e -> {
            if (e instanceof NamingEvent ne) {
                String list = ne.getInstances().stream()
                        .map(Watcher::fmt).sorted().collect(Collectors.joining("  "));
                System.out.println(now() + " PUSH inventory-svc [" + ne.getInstances().size() + "] " + list);
            }
        });
        ConfigService cs = NacosFactory.createConfigService(p);
        cs.addListener("inventory-svc.yaml", "DEFAULT_GROUP", new AbstractListener() {
            @Override public void receiveConfigInfo(String c) {
                System.out.println(now() + " CONFIG inventory-svc.yaml -> " + c.replace("\n", " | "));
            }
        });
        System.out.println(now() + " watching");
        Thread.currentThread().join();
    }

    static String fmt(Instance i) {
        return i.getPort() + (i.isHealthy() ? "" : "(unhealthy)") + (i.isEnabled() ? "" : "(disabled)") + " w=" + i.getWeight();
    }

    /** 行首是毫秒时间戳，run.sh 用它和自己记下的动作时刻相减 */
    static String now() { return System.currentTimeMillis() + " " + LocalTime.now().format(T); }
}
