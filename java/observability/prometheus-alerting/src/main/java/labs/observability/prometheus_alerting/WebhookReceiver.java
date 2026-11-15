package labs.observability.prometheus_alerting;

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代替“值班手机”：Alertmanager 的 webhook 打到这里，每收到一条通知打一行：
 * 收到的时刻、整条通知的状态、里面每个告警的名字、状态和实例。
 * 用法：WebhookReceiver <端口>
 */
public class WebhookReceiver {
    private static final Pattern ALERT = Pattern.compile(
            "\\{\"status\":\"(\\w+)\",\"labels\":\\{([^}]*)\\}");

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/alert", ex -> {
            String body;
            try (InputStream in = ex.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            StringBuilder line = new StringBuilder(LocalTime.now().withNano(0) + " webhook ");
            Matcher m = ALERT.matcher(body);
            while (m.find()) {
                String labels = m.group(2);
                line.append(" [").append(m.group(1)).append(' ')
                        .append(label(labels, "alertname")).append(' ').append(label(labels, "instance")).append(']');
            }
            System.out.println(line);
            System.out.flush();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        System.out.println(LocalTime.now().withNano(0) + " webhook receiver on :" + port);
    }

    private static String label(String labels, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(labels);
        return m.find() ? m.group(1) : "-";
    }
}
