package labs.observability.prometheus_alerting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每秒问一次 Prometheus 的 /api/v1/alerts，告警状态一变（出现 pending、变成 firing、消失）就打一行。
 * 用法：AlertWatcher <prometheus 端口> <持续秒数>
 */
public class AlertWatcher {
    private static final Pattern A = Pattern.compile("\"alertname\":\"(\\w+)\".*?\"state\":\"(\\w+)\"");

    public static void main(String[] args) throws Exception {
        String url = "http://localhost:" + args[0] + "/api/v1/alerts";
        int seconds = Integer.parseInt(args[1]);
        HttpClient http = HttpClient.newHttpClient();
        Map<String, String> last = new HashMap<>();
        for (int i = 0; i < seconds; i++) {
            Map<String, String> now = new HashMap<>();
            try {
                String body = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
                for (String part : body.split("\\{\"labels\"")) {
                    Matcher m = A.matcher(part);
                    if (m.find()) now.put(m.group(1), m.group(2));
                }
            } catch (Exception e) {
                System.out.println(LocalTime.now().withNano(0) + " prometheus 连不上: " + e);
            }
            Set<String> names = new HashSet<>(last.keySet());
            names.addAll(now.keySet());
            for (String n : names) {
                String a = last.getOrDefault(n, "inactive"), b = now.getOrDefault(n, "inactive");
                if (!a.equals(b)) System.out.println(LocalTime.now().withNano(0) + " rule  " + n + "  " + a + " -> " + b);
            }
            System.out.flush();
            last = now;
            Thread.sleep(1000);
        }
    }
}
