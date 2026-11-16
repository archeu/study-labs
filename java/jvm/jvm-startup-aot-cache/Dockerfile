# 同一个 order-svc，三种启动方式：plain（什么都不做）、cds（JDK 21 的 AppCDS 动态归档）、aot（JDK 25 的 AOT 缓存，JEP 483/514/515）
# 构建：docker build --target plain|cds|aot --build-arg JRE=<运行时镜像> .
ARG JRE=eclipse-temurin:21.0.13_9-jre-noble

FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests
# CDS / AOT 缓存都要求类路径是一个个普通 jar，不能是 fat jar 里的嵌套 jar，所以先拆开
RUN java -Djarmode=tools -jar target/order-svc.jar extract --layers --destination /x

FROM ${JRE} AS plain
WORKDIR /app
COPY --from=build /x/dependencies/ ./
COPY --from=build /x/spring-boot-loader/ ./
COPY --from=build /x/snapshot-dependencies/ ./
COPY --from=build /x/application/ ./
ENTRYPOINT ["java", "-jar", "order-svc.jar"]

# JDK 21：训练一次，启动到 Spring 上下文刷新完就退出，把加载过的类写进归档
FROM plain AS cds
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar order-svc.jar
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "order-svc.jar"]

# JDK 25：一步训练（JEP 514），缓存里有加载、链接好的类和方法的运行画像（JEP 515）
FROM plain AS aot
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar order-svc.jar
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "order-svc.jar"]
