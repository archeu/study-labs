package labs.juc.threadlocal_leak;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 串号：模拟 Tomcat 的 2 个 exec 线程。过滤器只在请求带 X-User 头时 set，
 * 不带头的请求本应是“未登录”（null）。
 *   java ... Mixup          不 remove：后面的匿名请求读到前面别人的用户号
 *   java ... Mixup remove   try/finally remove：匿名请求都是 null
 */
public class Mixup {
    static final ThreadLocal<Long> USER = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        final boolean remove = args.length > 0 && args[0].equals("remove");
        final AtomicInteger seq = new AtomicInteger();
        ExecutorService exec = Executors.newFixedThreadPool(2,
                r -> new Thread(r, "exec-" + seq.incrementAndGet()));
        // 请求序列：两个登录用户，然后 4 个不带头的请求
        Long[] headers = {1024L, 2048L, null, null, null, null};
        for (int i = 0; i < headers.length; i++) {
            final int no = i + 1;
            final Long header = headers[i];
            exec.submit(() -> handle(no, header, remove)).get(); // 一个接一个，便于看清
            Thread.sleep(5);
        }
        exec.shutdown();
        exec.awaitTermination(1, TimeUnit.SECONDS);
    }

    static void handle(int no, Long header, boolean remove) {
        if (header != null) USER.set(header);          // 过滤器：有头才 set
        try {
            Long user = USER.get();                     // 业务代码：下单记到谁头上
            String verdict = header == null && user != null ? "  <-- 串号！" : "";
            System.out.printf("请求 %d  头 X-User=%-5s 线程 %s  下单用户=%s%s%n",
                    no, header, Thread.currentThread().getName(), user, verdict);
        } finally {
            if (remove) USER.remove();                  // 第 11 站：清掉
        }
    }
}
