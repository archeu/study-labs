import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 最小压测机。两种发法：
 *   java Load.java closed 50  30 http://localhost:8090/work      50 个用户，各自“等回来再发下一个”
 *   java Load.java open   600 30 http://localhost:8090/work      每秒 600 个，到点就发，不管上一个回没回来
 * 每秒打一行：这一秒完成了几个、它们的 P50 / P99、此刻还有几个没回来。
 */
public class Load {
    static final ConcurrentLinkedQueue<Long> done = new ConcurrentLinkedQueue<>();
    static final AtomicInteger inflight = new AtomicInteger(), errors = new AtomicInteger();

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        int n = Integer.parseInt(a[1]), secs = Integer.parseInt(a[2]);
        URI uri = URI.create(a[3]);
        boolean post = a.length > 4 && a[4].equals("POST");
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        long start = System.nanoTime(), end = start + secs * 1_000_000_000L;

        if (mode.equals("closed")) {
            for (int i = 0; i < n; i++)
                Thread.ofVirtual().start(() -> {
                    while (System.nanoTime() < end) call(http, uri, post, System.nanoTime());
                });
        } else {
            Thread.ofPlatform().start(() -> {
                for (long k = 0; ; k++) {
                    long due = start + k * 1_000_000_000L / n;          // 第 k 个请求“应该”在这一刻发出
                    if (due > end) break;
                    while (System.nanoTime() < due) Thread.onSpinWait();
                    Thread.ofVirtual().start(() -> call(http, uri, post, due)); // 从计划时刻开始计时
                }
            });
        }
        System.out.println("  秒   完成/s   P50(ms)   P99(ms)   在途   错误");
        for (int s = 1; s <= secs; s++) {
            Thread.sleep(Math.max(0, (start + s * 1_000_000_000L - System.nanoTime()) / 1_000_000));
            List<Long> l = new ArrayList<>();
            for (Long x; (x = done.poll()) != null; ) l.add(x);
            Collections.sort(l);
            System.out.printf("%4d %8d %9d %9d %6d %6d%n", s, l.size(), pct(l, 50), pct(l, 99), inflight.get(), errors.getAndSet(0));
        }
        System.exit(0);
    }

    static void call(HttpClient http, URI uri, boolean post, long t0) {
        inflight.incrementAndGet();
        try {
            URI u = post ? URI.create(uri + "?uid=" + ThreadLocalRandom.current().nextInt(1000)) : uri;
            HttpRequest.Builder b = HttpRequest.newBuilder(u);
            HttpResponse<String> r = http.send(post ? b.POST(HttpRequest.BodyPublishers.noBody()).build() : b.build(),
                                               HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) errors.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
        } finally {
            inflight.decrementAndGet();
            done.add((System.nanoTime() - t0) / 1_000_000);
        }
    }

    static long pct(List<Long> l, int p) {
        return l.isEmpty() ? 0 : l.get(Math.min(l.size() - 1, (int) Math.ceil(l.size() * p / 100.0) - 1));
    }
}
