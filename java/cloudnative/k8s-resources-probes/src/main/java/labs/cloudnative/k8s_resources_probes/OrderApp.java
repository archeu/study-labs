package labs.cloudnative.k8s_resources_probes;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** order-svc 的“埋雷版”：能吃内存、能烧 CPU、能故意启动得很慢。只给实验用。 */
@SpringBootApplication
@RestController
public class OrderApp {

    static final List<byte[]> HELD = new ArrayList<>();

    @GetMapping("/api/orders/{id}")
    Map<String, Object> get(@PathVariable("id") long id) {
        return Map.of("id", id, "status", "PAID");
    }

    /** 每次留住 mb MB 的数组（写满，确保真的占了物理内存），模拟没上限的本地缓存。 */
    @PostMapping("/api/debug/hog")
    synchronized Map<String, Object> hog(@RequestParam(name = "mb", defaultValue = "32") int mb) {
        byte[] b = new byte[mb * 1024 * 1024];
        for (int i = 0; i < b.length; i += 4096) b[i] = 1;
        HELD.add(b);
        Runtime rt = Runtime.getRuntime();
        return Map.of("heldMb", HELD.size() * (long) mb,
                "heapUsedMb", (rt.totalMemory() - rt.freeMemory()) >> 20,
                "heapMaxMb", rt.maxMemory() >> 20);
    }

    /** 忙算 ms 毫秒，给 HPA 一点 CPU 看。 */
    @GetMapping("/api/burn")
    Map<String, Object> burn(@RequestParam(name = "ms", defaultValue = "50") long ms) {
        long end = System.nanoTime() + ms * 1_000_000L, x = 0;
        while (System.nanoTime() < end) x += System.nanoTime() % 7;
        return Map.of("burnedMs", ms, "x", x % 10);
    }

    @GetMapping("/api/jvm")
    Map<String, Object> jvm() {
        Runtime rt = Runtime.getRuntime();
        return Map.of("heapMaxMb", rt.maxMemory() >> 20,
                "cpus", rt.availableProcessors(),
                "args", ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    public static void main(String[] args) throws InterruptedException {
        // 模拟启动慢：预热缓存、加载规则……在 HTTP 端口打开之前就要花这么久
        int delay = Integer.parseInt(System.getenv().getOrDefault("STARTUP_DELAY_SEC", "0"));
        if (delay > 0) {
            System.out.println("warming up for " + delay + " s before starting the web server");
            Thread.sleep(delay * 1000L);
        }
        SpringApplication.run(OrderApp.class, args);
    }
}
