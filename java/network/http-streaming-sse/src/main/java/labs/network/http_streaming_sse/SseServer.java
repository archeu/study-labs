package labs.network.http_streaming_sse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 一个只用 JDK 自带 HttpServer 的 SSE 服务：模仿模型“一边写一边推”。
 *
 * <pre>
 *   GET /stream?flush=true    每写一个事件就 flush，客户端一块一块收到
 *   GET /stream?flush=false   事件先进 64 KB 的缓冲区，不 flush，客户端最后一次性收到
 * </pre>
 * 每个事件间隔 400 ms，一共 6 个：模仿模型每 400 ms 吐一段字。
 */
public class SseServer {
    static final String[] PIECES = {"是", "，GC", "导致", "的。", "依据：20:12:43", " Pause Full 1873ms"};

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8095;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/stream", SseServer::stream);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("SSE 服务已启动：http://127.0.0.1:" + port + "/stream?flush=true");
    }

    static void stream(HttpExchange ex) throws IOException {
        boolean flush = !String.valueOf(ex.getRequestURI().getQuery()).contains("flush=false");
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);                    // 长度写 0：不知道总长度，走 chunked 分块传输
        try (OutputStream raw = ex.getResponseBody();
             OutputStream out = new BufferedOutputStream(raw, 64 * 1024)) {
            write(out, "message_start", "{\"type\":\"message_start\"}", flush);
            for (String p : PIECES) {
                sleep(400);
                write(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"" + p + "\"}}", flush);
            }
            write(out, "message_stop", "{\"type\":\"message_stop\"}", flush);
        }                                                  // close 时才把缓冲区里剩下的发出去
    }

    static void write(OutputStream out, String event, String data, boolean flush) throws IOException {
        // SSE 的格式：几行 "字段: 值"，一个空行结束一个事件
        out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        if (flush) out.flush();                            // 不 flush，字节就躺在缓冲区里
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
