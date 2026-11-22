# HTTP 流式与 SSE：一个响应怎么一块一块地推

对应页面：[HTTP 流式与 SSE：一个响应怎么一块一块地推](https://study.ainiya.de/network/http-streaming-sse.html)

只用 JDK 17 自带的 `HttpServer` 和 `HttpClient`，不加依赖。

- `SseServer`：每 400 ms 写一个 SSE 事件，共 8 个；`?flush=true` 每个事件后 flush，`?flush=false` 写进 64 KB 缓冲不 flush。
- `SseClient`：按行读一个 SSE 响应，打印每个事件到达的毫秒数；第二个参数有值时发 POST（可以直接读本机 llama-server 的 `/v1/messages` 流）。

## 怎么跑

```bash
cd java && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl network/http-streaming-sse compile
CP=network/http-streaming-sse/target/classes
java -cp $CP labs.network.http_streaming_sse.SseServer &          # 127.0.0.1:8095
java -cp $CP labs.network.http_streaming_sse.SseClient 'http://127.0.0.1:8095/stream?flush=true'
java -cp $CP labs.network.http_streaming_sse.SseClient 'http://127.0.0.1:8095/stream?flush=false'
curl --raw -i -N 'http://127.0.0.1:8095/stream?flush=true'        # 看分块传输的块长度
java -cp $CP labs.network.http_streaming_sse.SseClient http://127.0.0.1:8090/v1/messages \
  '{"model":"x","max_tokens":12,"stream":true,"messages":[{"role":"user","content":"用一句话说说 Full GC"}]}'
```

## 预期输出

2026-11-22，2 核 ARM，OpenJDK 17：

```
flush=true：   572 ms “是”、972 ms “，GC”、1373 ms “导致”……每 400 ms 一个，2576 ms 结束
flush=false：  响应头 98 ms 就到了，但 8 个事件全部在 2483～2487 ms 之间一起到
curl --raw：   Transfer-encoding: chunked，块长度 35（=53 字节）、58（=88 字节）……
```

## 练习

1. 加心跳：事件之间每 200 ms 发一行 `: ping`，客户端忽略冒号开头的行。
2. 客户端读到第 3 个事件就关连接，服务端打印“客户端断开”并停止写入。
3. 用 Spring Boot 的 `SseEmitter` 写同样的接口，用 `SseClient` 对比到达时间。
