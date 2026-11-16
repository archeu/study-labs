# 改一行代码，只推一层：Docker 镜像分层与多阶段构建

对应页面：[改一行代码，只推一层：Docker 镜像分层与多阶段构建](https://study.ainiya.de/cloudnative/docker-image-layers.html)（J14 第 1 个知识点，全景第 2、3、7 站）

一个最小的 order-svc（Spring Boot 3.5.16，web + actuator）和三份 Dockerfile：

| 文件 | 写法 |
|---|---|
| `docker/Dockerfile.single` | 单阶段：`FROM maven`，`COPY . .`，`RUN mvn package` |
| `docker/Dockerfile.fatjar` | 多阶段，整个 fat jar 一层 COPY |
| `docker/Dockerfile.layered` | 多阶段 + Spring Boot 四层（dependencies / spring-boot-loader / snapshot-dependencies / application） |

这个模块的 `pom.xml` 不继承 `java/pom.xml`（docker build 的上下文只有本目录），父 POM 是 `spring-boot-starter-parent`。

## 怎么跑

要 Docker 23 以上（BuildKit）。第一次会拉 `maven:3.9.16-eclipse-temurin-21-noble`、`eclipse-temurin:21.0.13_9-jre-noble`、`registry:3.0.0`。

```bash
./run.sh                  # 默认 sudo docker；在 docker 组里就 DOCKER=docker ./run.sh
cat out/results.txt
./run.sh clean            # 删掉本实验的镜像（lab/*、127.0.0.1:55000/*）和容器
```

`run.sh` 做六件事：三种写法各构建 1.8.3；改 `VERSION` 成 1.8.4 再构建，比较层的摘要；推到本地仓库 `127.0.0.1:55000`，按 manifest 算第二次推送新传了多少；删文件和构建参数两个坑；shell 形式和 exec 形式 ENTRYPOINT 的停机时间；overlay2 可写层的 `docker diff`。

## 预期输出

2026-11-15，2 核 ARM（aarch64），Docker 27.3.1：

```
single:  镜像 650 MB，12 层 ；改一行代码：重建 17.5 s，变 2 层，要新传 68.23 MB
fatjar:  镜像 336 MB，7 层  ；改一行代码：重建 5.3 s， 变 1 层，要新传 21.30 MB
layered: 镜像 336 MB，11 层 ；改一行代码：           变 1 层，要新传 3905 字节
基础镜像 312 MB → 生成 50 MB 再删掉以后 362 MB
RUN |1 DB_PASSWORD=s3cr3t-pw /bin/sh -c …        （docker history 里看得到）
shell 形式：1 号进程 /bin/sh -c java …，docker stop 10.2 s，退出码 137
exec 形式：1 号进程 java -jar order-svc.jar，docker stop 0.2 s，退出码 143
```

layered 的构建阶段和 fatjar 完全一样，第二个构建直接命中缓存，所以它的重建耗时不拿来比较。

## 练习

先猜结果，再运行。

1. 把 `Dockerfile.layered` 里 `application` 那行挪到 `dependencies` 前面，改一行代码重建、重推：这次推了多少？
2. 在 `pom.xml` 里加 `spring-boot-starter-validation`，看 layered 哪几层变了。
3. 用 `RUN --mount=type=secret,id=db` 改写构建参数那个坑，`docker build --secret id=db,src=…`，再 `docker history --no-trunc` 确认看不到密码。
