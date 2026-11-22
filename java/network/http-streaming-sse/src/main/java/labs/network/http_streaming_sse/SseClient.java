package labs.network.http_streaming_sse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * 用 JDK 自带 HttpClient 按行读一个 SSE 响应，打印每行到达的时刻，并把行拼成事件。
 *
 * <pre>
 *   java ... SseClient http://127.0.0.1:8095/stream?flush=true
 *   java ... SseClient http://127.0.0.1:8095/stream?flush=false
 *   java ... SseClient http://127.0.0.1:8090/v1/messages '{"model":"x","max_tokens":20,"stream":true,
 *                                                         "messages":[{"role":"user","content":"说一句话"}]}'
 * </pre>
 * 第二个参数有值就发 POST（例如本机 llama-server 的 /v1/messages，它的 SSE 格式和 Claude 一样）。
 */
public class SseClient {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(args[0]))
                .header("Accept", "text/event-stream");
        if (args.length > 1) {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(args[1]));
        }
        long t0 = System.nanoTime();
        // ofLines：响应体按行交给我们，来一行处理一行，不等整个响应结束
        HttpResponse<Stream<String>> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofLines());
        System.out.printf("%6d ms  响应头到了：HTTP %d，Content-Type=%s%n", ms(t0), resp.statusCode(),
                resp.headers().firstValue("content-type").orElse("?"));
        StringBuilder data = new StringBuilder();
        String[] event = {"message"};
        int[] count = {0};
        resp.body().forEach(line -> {
            if (line.startsWith("event:")) {
                event[0] = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && data.length() > 0) {     // 空行 = 一个事件结束
                count[0]++;
                String d = data.toString();
                System.out.printf("%6d ms  事件 %-20s %s%n", ms(t0), event[0], d.length() > 70 ? d.substring(0, 70) + "…" : d);
                data.setLength(0);
                event[0] = "message";
            }
        });
        System.out.printf("%6d ms  连接结束，一共 %d 个事件%n", ms(t0), count[0]);
    }

    static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
