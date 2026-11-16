# 容器里的 JVM 怎么起得快：CDS、AOT 缓存和别的路

对应页面：[容器里的 JVM 怎么起得快：CDS、AOT 缓存和别的路](https://study.ainiya.de/jvm/jvm-startup-aot-cache.html)（J14 第 4 个知识点第二圈，全景第 7 站）

一个最小的 order-svc（Spring Boot 3.5.16，web + actuator），一个多目标的 `Dockerfile`：

| 目标 | 运行时 | 启动方式 |
|---|---|---|
| `plain` | JRE 21 或 25 | 什么都不做（JDK 自带默认 CDS 归档） |
| `cds` | JRE 21 | 训练出 `app.jsa`（`-XX:ArchiveClassesAtExit`），运行 `-XX:SharedArchiveFile` |
| `aot` | JRE 25 | 训练出 `app.aot`（`-XX:AOTCacheOutput`，JEP 514），运行 `-XX:AOTCache` |

训练都在 `docker build` 里做，`-Dspring.context.exit=onRefresh` 让应用启动完就退出。模块不继承 `java/pom.xml`（docker build 的上下文只有本目录）。

## 怎么跑

```bash
./run.sh          # 约 6 分钟：构建 4 个镜像，每种方式在 --cpus=1 --memory=512m 的容器里起 5 次
./run.sh clean
```

## 预期输出

2026-11-16，2 核 ARM（aarch64），Docker 27.3.1，Temurin 21.0.13 / 25.0.5：

```
归档大小：app.jsa 36.0 MB，app.aot 55.1 MB
JDK 21，什么都不做    中位数 6.207 s
JDK 21，懒加载 Bean   中位数 5.91 s
JDK 21，AppCDS 归档   中位数 4.483 s
JDK 25，什么都不做    中位数 6.37 s
JDK 25，AOT 缓存      中位数 3.763 s
21-plain：加载了 7470 个类，其中 1278 个来自归档 / 缓存
21-cds：  加载了 7293 个类，其中 6889 个来自归档 / 缓存
25-aot：  加载了 7706 个类，其中 7438 个来自归档 / 缓存
```

手动试一下缓存失效：

```bash
docker run --rm --entrypoint sh lab/startup:25-aot -c 'touch lib/spring-web-*.jar; java -XX:AOTCache=app.aot -Dspring.context.exit=onRefresh -jar order-svc.jar 2>&1 | head -3'
# [warning][aot] This file is not the one used while building the AOT cache: 'lib/spring-web-6.2.19.jar', timestamp has changed
# [error  ][aot] shared class paths mismatch …
```

## 练习

先猜结果，再运行。

1. 给 `run.sh` 加一步：来自缓存的类少于 5000 个就 `exit 1`；再只给运行阶段换一个 JRE 小版本，看它能不能拦住。
2. 把训练改成“起服务 → 打 20 次 `/api/orders/1` → 正常退出”，比较第一次请求的耗时。
3. 把 `--cpus=1` 改成 `0.5` 和 `2`，缓存省下的比例变不变？
