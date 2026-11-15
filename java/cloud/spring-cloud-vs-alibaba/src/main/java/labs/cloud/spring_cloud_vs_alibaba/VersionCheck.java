package labs.cloud.spring_cloud_vs_alibaba;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.SpringVersion;

/**
 * 只做一件事：用当前这组版本把容器起起来，打印各家族的实际版本，然后退出。
 * 版本不对齐时，Spring Cloud 的兼容性检查会让启动失败，run.sh 把失败原因摘出来。
 */
@SpringBootApplication
public class VersionCheck {
    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(VersionCheck.class, args);
        System.out.println("RESULT 启动成功：Spring Boot " + SpringBootVersion.getVersion()
                + " · Spring Framework " + SpringVersion.getVersion()
                + " · Spring Cloud Commons " + ver(org.springframework.cloud.client.discovery.DiscoveryClient.class)
                + " · SCA Nacos " + ver(com.alibaba.cloud.nacos.NacosDiscoveryProperties.class)
                + " · Nacos 客户端 " + com.alibaba.nacos.common.utils.VersionUtils.version
                + " · Sentinel " + ver(com.alibaba.csp.sentinel.SphU.class));
        ctx.close();
    }

    static String ver(Class<?> c) {
        String v = c.getPackage().getImplementationVersion();
        if (v != null) return v;
        String p = c.getProtectionDomain().getCodeSource().getLocation().getPath();   // 退一步：从 jar 名里取
        return p.substring(p.lastIndexOf('/') + 1).replace(".jar", "").replace("!", "");
    }
}
