# 和上一页的 Dockerfile.layered 一样：多阶段 + 四层
FROM maven:3.9.16-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests
# Spring Boot 3.3 起用 tools 模式；拆出 application.jar（瘦 jar）+ lib/，按层放进四个目录
RUN java -Djarmode=tools -jar target/order-svc.jar extract --layers --destination /x

FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /x/dependencies/ ./
COPY --from=build /x/spring-boot-loader/ ./
COPY --from=build /x/snapshot-dependencies/ ./
COPY --from=build /x/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "order-svc.jar"]
