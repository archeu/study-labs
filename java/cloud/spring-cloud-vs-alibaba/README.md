# 五个部件来自两个家族：Spring Cloud 与 Spring Cloud Alibaba 怎么选

对应页面：[五个部件来自两个家族：Spring Cloud 与 Spring Cloud Alibaba 怎么选](https://study.ainiya.de/cloud/spring-cloud-vs-alibaba.html)（J10 全景第 1 站以及全部部件）

## 里面有什么

- `VersionCheck`：一个最小的 Spring Boot 应用，依赖 web、Nacos 注册、Sentinel、LoadBalancer；起来以后打印 Boot、Spring Framework、Spring Cloud Commons、Spring Cloud Alibaba、Nacos 客户端、Sentinel 的实际版本，然后退出。
- `run.sh`：用 `-Dspring-boot.version=… -Dspring-cloud.version=… -Dspring-cloud-alibaba.version=…` 换 5 种组合，各编译、启动一次（用 `mvn -f pom.xml` 只构建这个模块，免得版本属性影响仓库里别的模块）。

需要 Nacos：`../nacos-registry-config/run.sh up`。

## 怎么跑

```bash
cd java/cloud/spring-cloud-vs-alibaba
./run.sh demo        # 第一次要下载 Boot 4 的依赖，约 5 分钟
```

## 预期输出

2 核 ARM 虚拟机，JDK 17：

```
== A：Boot 3.5.16 + Spring Cloud 2025.0.3 + Spring Cloud Alibaba 2025.0.0.0
   启动成功：Spring Boot 3.5.16 · Spring Framework 6.2.19 · Spring Cloud Commons 4.3.3 · SCA Nacos 2025.0.0.0 · Nacos 客户端 3.0.3 · Sentinel 1.8.9
== B：Boot 4.0.9 + Spring Cloud 2025.0.3 + Spring Cloud Alibaba 2025.0.0.0
  启动失败：
    2026-11-15T12:52:24.662+07:00  WARN 1457152 --- [version-check] [           main] ConfigServletWebServerApplicationContext : Exception encountered during context initialization - cancelling refresh attempt: java.lang.IllegalStateExcepti
    java.lang.IllegalStateException: Error processing condition on org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration.simpleDiscoveryProperties
== C：Boot 4.0.9 + Spring Cloud 2025.1.4 + Spring Cloud Alibaba 2025.1.0.0
   启动成功：Spring Boot 4.0.9 · Spring Framework 7.0.10 · Spring Cloud Commons 5.0.4 · SCA Nacos 2025.1.0.0 · Nacos 客户端 3.1.1 · Sentinel 1.8.9
== D：Boot 4.1.2 + Spring Cloud 2025.1.4 + Spring Cloud Alibaba 2025.1.0.0
   启动成功：Spring Boot 4.1.2 · Spring Framework 7.0.10 · Spring Cloud Commons 5.0.4 · SCA Nacos 2025.1.0.0 · Nacos 客户端 3.1.1 · Sentinel 1.8.9
== E：Boot 3.5.16 + Spring Cloud 2025.1.4 + Spring Cloud Alibaba 2025.1.0.0
  启动失败：
    2026-11-15T12:53:15.794+07:00  WARN 1457913 --- [version-check] [           main] ConfigServletWebServerApplicationContext : Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.fac
    org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationAutoConfiguration': Unsatisfied dependency expressed through field 
```

失败的根因（`data/run-*.log` 里最后一个 `Caused by`）：

- B：`ClassNotFoundException: org.springframework.boot.autoconfigure.web.ServerProperties`。Spring Cloud 2025.0 按 Boot 3 的包结构写的，这个类在 Boot 4 里换了位置。
- E：`ClassNotFoundException: org.springframework.boot.web.server.context.WebServerInitializedEvent`。Spring Cloud 2025.1 按 Boot 4 的新位置找，Boot 3.5 里没有。
- 两种错配都在 Spring Cloud 的兼容性检查（`spring.cloud.compatibility-verifier`）给出提示之前，就因为类找不到崩了。

第一次跑时还有两个坑：一是用 `mvn -pl` 在父工程里构建，`-D` 改的版本属性让别的模块的 POM 也跟着解析失败；二是应用没带 web 起步依赖，Boot 4 把 `WebServerInitializedEvent` 拆进了 `spring-boot-web-server` 模块，结果 C、D 也起不来。两处都已修好。

## 练习

1. 加 `--spring.cloud.compatibility-verifier.enabled=false` 再跑 B，报错变了吗？说明这个开关能不能救一个真不兼容的组合。
2. 把 E 里的 Spring Cloud Alibaba 换回 2025.0.0.0（Spring Cloud 仍是 2025.1.4），会怎样？
3. 用 `mvn -f pom.xml dependency:tree -Dincludes=com.alibaba.nacos` 看三种组合下 Nacos 客户端分别是哪个版本，是谁带进来的。
