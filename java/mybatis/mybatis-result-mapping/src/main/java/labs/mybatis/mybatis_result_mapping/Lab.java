package labs.mybatis.mybatis_result_mapping;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 交易靶场·查订单和明细、导出流水。不起 Web，H2 内存库（MySQL 模式）。
 * 场景：nplus1、lazy、join、auto、limit、handler、stream。不带参数全跑。
 */
@SpringBootApplication
public class Lab {
    static OrderMapper m;
    static OrderService svc;

    static void log(String s) { System.out.println(s); }

    public static void main(String[] args) throws Exception {
        List<String> want = args.length == 0
                ? List.of("nplus1", "lazy", "join", "auto", "limit", "handler", "stream", "pages")
                : List.of(args);
        log("JDK " + System.getProperty("java.version") + " · Spring " + org.springframework.core.SpringVersion.getVersion()
                + " · MyBatis " + org.apache.ibatis.session.Configuration.class.getPackage().getImplementationVersion());
        SpringApplication app = new SpringApplication(Lab.class);
        try (ConfigurableApplicationContext ctx = app.run()) {
            m = ctx.getBean(OrderMapper.class);
            svc = ctx.getBean(OrderService.class);
            warmUp();
            if (want.contains("nplus1")) nplus1();
            if (want.contains("lazy")) lazy();
            if (want.contains("join")) join();
            if (want.contains("auto")) auto();
            if (want.contains("limit")) limit();
            if (want.contains("handler")) handler();
            if (want.contains("stream")) stream();
            if (want.contains("pages")) pages();
        }
    }

    static void warmUp() {
        for (int i = 0; i < 20; i++) { m.listOrdersNested(); m.listOrdersJoin(); }
    }

    static String counts() { return "SQL " + SqlCounter.sql + " 条，借连接 " + SqlCounter.conns.size() + " 次"; }

    static int itemCount(List<Order> os) { return os.stream().mapToInt(o -> o.getItems().size()).sum(); }

    /** 立即加载的嵌套查询 vs 一条 JOIN：同样拿到 100 笔订单和明细。 */
    static void nplus1() {
        log("\n== nplus1：100 笔订单连明细，嵌套查询 vs JOIN");
        for (String way : List.of("嵌套查询", "JOIN")) {
            long best = Long.MAX_VALUE; List<Order> os = null;
            for (int r = 0; r < 30; r++) {
                SqlCounter.reset();
                long t = System.nanoTime();
                os = way.equals("JOIN") ? m.listOrdersJoin() : m.listOrdersNested();
                best = Math.min(best, System.nanoTime() - t);
            }
            log(String.format("  %s：订单 %d 笔，明细 %d 件，%s，最快一次 %.2f ms", way, os.size(), itemCount(os), counts(), best / 1e6));
            log("    前几条 SQL：" + SqlCounter.first);
            Order o = os.stream().filter(x -> x.getId() == 9527).findFirst().get();
            log("    9527：" + o + " " + o.getItems());
        }
    }

    /** 懒加载：查的时候 1 条，碰的时候才查；在不在事务里，借连接的次数不一样。 */
    static void lazy() throws Exception {
        log("\n== lazy：懒加载");
        SqlCounter.reset();
        List<Order> os = svc.lazyNoTx();
        log("  查完订单：" + counts() + "；对象的类：" + os.get(0).getClass().getName());
        os.get(0).getStatus();
        log("  调 getStatus()：" + counts());
        os.get(1).toString();
        log("  调第 2 笔的 toString()（它不碰 items）：" + counts());
        itemCount(os);
        log("  不在事务里，把 100 笔的 items 都碰一遍：" + counts());
        SqlCounter.reset();
        int n = svc.lazyInTx();
        log("  在 @Transactional 里做同样的事：明细 " + n + " 件，" + counts());
        try {
            String json = new ObjectMapper().writeValueAsString(svc.lazyNoTx().get(0));
            log("  Jackson 序列化懒加载对象：" + json);
        } catch (Exception e) {
            log("  Jackson 序列化懒加载对象：" + e.getClass().getSimpleName() + ": " + e.getMessage().split("\n")[0]);
        }
    }

    static void join() {
        log("\n== join：JOIN 回来多少行，折成多少个对象");
        SqlCounter.reset();
        List<Order> os = m.listOrdersJoin();
        log("  JOIN 行数 = 明细件数 = " + itemCount(os) + "，折叠成订单 " + os.size() + " 笔；" + counts());
        log("  对象的类：" + os.get(0).getClass().getName());
    }

    static void auto() {
        log("\n== auto：明细的 resultMap 只写 <id>，其余列指望自动映射（autoMappingBehavior 默认 PARTIAL）");
        List<Order> os = m.listOrdersJoinAuto();
        Order o = os.stream().filter(x -> x.getId() == 9527).findFirst().get();
        log("  9527 的明细：" + o.getItems());
    }

    static void limit() {
        log("\n== limit：想要“前 10 笔订单”");
        List<Order> a = m.listOrdersJoinLimit(10);
        StringBuilder sb = new StringBuilder();
        for (Order o : a) sb.append(o.getId()).append('×').append(o.getItems().size()).append(' ');
        log("  JOIN 后 LIMIT 10：拿到 " + a.size() + " 笔订单：" + sb.toString().trim());
        List<Order> b = m.listOrdersPageThenJoin(10);
        sb.setLength(0);
        for (Order o : b) sb.append(o.getId()).append('×').append(o.getItems().size()).append(' ');
        log("  先分订单页再 JOIN：拿到 " + b.size() + " 笔订单：" + sb.toString().trim());
        Map<Long, Integer> real = new LinkedHashMap<>();
        for (long id : List.of(9504L, 9505L)) real.put(id, m.listItems(id).size());
        log("  核对：9504、9505 实际明细件数 " + real);
    }

    static void handler() {
        log("\n== handler：嵌套结果映射 + 自定义 ResultHandler");
        try {
            m.scanOrdersJoin(ctx -> { });
            log("  没报错");
        } catch (Exception e) {
            Throwable c = e; while (c.getCause() != null) c = c.getCause();
            log("  " + c.getClass().getSimpleName() + ": " + c.getMessage().split("\n")[0]);
        }
    }

    static long usedAfterGc() {
        for (int i = 0; i < 3; i++) System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static String mb(long b) { return String.format("%.2f MB", b / 1048576.0); }

    /** 分 10 页、每页 2 万条导出，每页用完就扔：在不在事务里，循环结束时堆差多少。 */
    static void pages() {
        log("\n== pages：分 10 页导出 20 万条，每页用完就扔；循环结束时量堆（GC 后）");
        for (int round = 1; round <= 2; round++) {
            long base = usedAfterGc();
            long a = svc.pages(Lab::usedAfterGc) - base;
            base = usedAfterGc();
            long b = svc.pagesInTx(Lab::usedAfterGc) - base;
            log("  第 " + round + " 轮：不在事务里 " + mb(a) + "；在一个 @Transactional 里 " + mb(b) + "；事务结束后再量 " + mb(usedAfterGc() - base));
        }
    }

    /** 导出 20 万条流水：一次装进 List、Cursor、ResultHandler；在第 10 万条时量一次 GC 后的堆。 */
    static void stream() throws Exception {
        log("\n== stream：导出 20 万条流水，在第 10 万条时量堆（GC 后）");
        for (int round = 1; round <= 2; round++) {
            long base = usedAfterGc();
            long t = System.nanoTime();
            List<PayFlow> all = m.listFlows();
            long sum = 0; long[] heap = new long[1];
            for (int i = 0; i < all.size(); i++) {
                sum += all.get(i).getAmount();
                if (i == 100_000) heap[0] = usedAfterGc() - base;
            }
            log(String.format("  第 %d 轮 selectList：%d 条，第 10 万条时堆比开始多 %s，sum=%d，耗时 %.0f ms（含两次量堆）",
                    round, all.size(), mb(heap[0]), sum, (System.nanoTime() - t) / 1e6));
            all = null;

            base = usedAfterGc();
            t = System.nanoTime();
            long s2 = svc.cursorInTx(100_000, x -> heap[0] = usedAfterGc() - 0);
            log(String.format("  第 %d 轮 Cursor（事务里）：第 10 万条时堆比开始多 %s，sum=%d，耗时 %.0f ms",
                    round, mb(heap[0] - base), s2, (System.nanoTime() - t) / 1e6));

            base = usedAfterGc();
            AtomicLong s3 = new AtomicLong();
            final long b3 = base;
            t = System.nanoTime();
            ResultHandler<PayFlow> h = ctx -> {
                s3.addAndGet(ctx.getResultObject().getAmount());
                if (ctx.getResultCount() == 100_001) heap[0] = usedAfterGc() - b3;
            };
            m.scanFlows(h);
            log(String.format("  第 %d 轮 ResultHandler：第 10 万条时堆比开始多 %s，sum=%d，耗时 %.0f ms",
                    round, mb(heap[0]), s3.get(), (System.nanoTime() - t) / 1e6));
        }
        try {
            svc.cursorNoTx();
            log("  Cursor（不在事务里）：没报错");
        } catch (Exception e) {
            log("  Cursor（不在事务里）：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
