package labs.spring.spring_tx_propagation;

import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

/**
 * 交易靶场·付款。不起 Web，H2 内存库（MySQL 模式），HikariCP 开 4 条连接。
 * 场景：matrix（传播 × 四种情况）、starve（REQUIRES_NEW 把池子借空）、iso（隔离级别何时生效）、
 * timeout（超时什么时候才检查）、listener（提交后写库）、validate、bad-listener。不带参数全跑。
 */
@SpringBootApplication
public class Lab {
    static DataSource ds;
    static final long ORDER = 9527, AMOUNT = 79800;

    static void log(String s) { System.out.println(s); }

    /** lab.validate=true 时打开事务管理器的 validateExistingTransaction（默认 false）。 */
    @Bean
    static BeanPostProcessor validateSwitch(org.springframework.core.env.Environment env) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof AbstractPlatformTransactionManager tm) {
                    tm.setValidateExistingTransaction(env.getProperty("lab.validate", Boolean.class, false));
                }
                return bean;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        List<String> want = args.length == 0
                ? List.of("matrix", "starve", "selflock", "iso", "timeout", "listener", "listener-noac", "validate", "bad-listener")
                : List.of(args);
        log("JDK " + System.getProperty("java.version") + " · Spring " + org.springframework.core.SpringVersion.getVersion());
        try (ConfigurableApplicationContext ctx = start()) {
            ds = ctx.getBean(DataSource.class);
            PayService pay = ctx.getBean(PayService.class);
            TradeMapper m = ctx.getBean(TradeMapper.class);
            log("事务管理器：" + ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class).getClass().getSimpleName());
            if (want.contains("matrix")) matrix(pay, m);
            if (want.contains("starve")) starve(pay, m);
            if (want.contains("selflock")) selflock(pay, m);
            if (want.contains("iso")) iso(pay);
            if (want.contains("timeout")) timeout(pay, m);
            if (want.contains("listener")) listener(pay, m);
        }
        if (want.contains("listener-noac")) listenerNoAutoCommit();
        if (want.contains("validate")) validate();
        if (want.contains("bad-listener")) badListener();
    }

    static ConfigurableApplicationContext start(String... props) {
        SpringApplication app = new SpringApplication(Lab.class);
        Properties p = new Properties();
        for (String kv : props) p.setProperty(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
        app.setDefaultProperties(p);
        return app.run();
    }

    static void reset(TradeMapper m) {
        m.resetOrder(ORDER, AMOUNT);
        for (long id = 9601; id <= 9608; id++) m.resetOrder(id, AMOUNT);
        m.clearPayLog();
        m.clearNotify();
    }

    // ---------------- 传播：4 种情况 × 3 种写法 ----------------
    record Case(String name, long paid, boolean logFails, boolean catchLogFail) {}

    static void matrix(PayService pay, TradeMapper m) {
        log("\n== matrix：pay(9527) 里写一条支付流水，流水用三种传播行为 ==");
        List<Case> cases = List.of(
                new Case("A 金额对，流水正常", AMOUNT, false, false),
                new Case("B 金额不符（付 1 元）", 100, false, false),
                new Case("C 金额对，流水失败，外层 catch", AMOUNT, true, true),
                new Case("D 金额对，流水失败，不 catch", AMOUNT, true, false));
        List<String> table = new ArrayList<>();
        for (Case c : cases) {
            for (PayService.LogMode mode : PayService.LogMode.values()) {
                reset(m);
                log("-- " + c.name() + " · 流水用 " + mode);
                String outcome;
                try {
                    outcome = "返回 " + pay.pay(ORDER, c.paid(), mode, c.logFails(), c.catchLogFail());
                } catch (Exception e) {
                    outcome = "抛 " + e.getClass().getSimpleName();
                }
                String row = String.format("%-26s %-13s 调用方看到：%-34s 订单=%-8s 流水=%d 条",
                        c.name(), mode, outcome, m.statusOf(ORDER), m.payLogCount(ORDER));
                log("   => " + row);
                table.add(row);
            }
        }
        log("\n汇总：");
        table.forEach(r -> log("  " + r));
    }

    // ---------------- 池子 4 条连接：4 个付款同时要第 2 条 ----------------
    static void starve(PayService pay, TradeMapper m) throws Exception {
        log("\n== starve：连接池 4 条，借不到等 1 s ==");
        for (int n : new int[] {3, 4}) {
            for (PayService.LogMode mode : List.of(PayService.LogMode.REQUIRES_NEW, PayService.LogMode.NESTED)) {
                reset(m);
                log("-- " + n + " 个付款同时进行，流水用 " + mode);
                CyclicBarrier barrier = new CyclicBarrier(n);
                ExecutorService pool = Executors.newFixedThreadPool(n, r -> new Thread(r, "pay-" + UUID.randomUUID().toString().substring(0, 4)));
                List<Future<String>> fs = new ArrayList<>();
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    long id = 9601 + i;
                    fs.add(pool.submit(() -> {
                        try { return pay.payThenLog(id, mode, barrier); }
                        catch (Exception e) {
                            Throwable root = e; while (root.getCause() != null) root = root.getCause();
                            return e.getClass().getSimpleName() + " ← " + root.getClass().getSimpleName() + ": " + root.getMessage();
                        }
                    }));
                }
                Map<String, Integer> results = new TreeMap<>();
                for (Future<String> f : fs) results.merge(f.get(), 1, Integer::sum);
                pool.shutdown();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int paid = 0; for (int i = 0; i < n; i++) if ("PAID".equals(m.statusOf(9601 + i))) paid++;
                log("   => 用时 " + ms + " ms，成功付款 " + paid + "/" + n + "，结果：" + results);
            }
        }
    }

    // ---------------- REQUIRES_NEW 改外层锁住的同一行 ----------------
    static void selflock(PayService pay, TradeMapper m) {
        log("\n== selflock：外层改了订单 9527，REQUIRES_NEW 的内层再改同一行 ==");
        reset(m);
        long t0 = System.nanoTime();
        String outcome;
        try { outcome = "返回 " + pay.payThenTouchOrderInNewTx(ORDER); }
        catch (Exception e) {
            Throwable root = e; while (root.getCause() != null) root = root.getCause();
            outcome = "抛 " + e.getClass().getSimpleName() + " ← " + root.getClass().getSimpleName() + ": "
                    + String.valueOf(root.getMessage()).lines().findFirst().orElse("");
        }
        log("   => " + outcome + "，用时 " + (System.nanoTime() - t0) / 1_000_000 + " ms，订单=" + m.statusOf(ORDER));
    }

    // ---------------- 隔离级别 ----------------
    static void iso(PayService pay) {
        log("\n== iso：隔离级别只在“开新事务”那一刻设置 ==");
        pay.isolationPeek();
        log("    事务结束、连接还回池子后再借一次：" + Conn.peek(ds));
    }

    // ---------------- 超时 ----------------
    static void timeout(PayService pay, TradeMapper m) {
        log("\n== timeout：@Transactional(timeout = 1)，方法里睡 1.5 s ==");
        for (boolean before : new boolean[] {true, false}) {
            reset(m);
            String outcome;
            long t0 = System.nanoTime();
            try { outcome = "返回 " + pay.payWithTimeout(ORDER, before); }
            catch (Exception e) { outcome = "抛 " + e.getClass().getSimpleName() + ": " + e.getMessage(); }
            log("   " + (before ? "先睡再发 UPDATE" : "先发 UPDATE 再睡") + " => " + outcome
                    + "，用时 " + (System.nanoTime() - t0) / 1_000_000 + " ms，订单=" + m.statusOf(ORDER));
        }
    }

    // ---------------- 提交后监听 ----------------
    static void listener(PayService pay, TradeMapper m) {
        log("\n== listener：付款提交后，两个同步 @TransactionalEventListener 各写一条通知记录 ==");
        reset(m);
        log("   付款返回 " + pay.pay(ORDER, AMOUNT, PayService.LogMode.REQUIRED, false, false));
        log("   之后查库：plain 写的 " + m.notifyCount(ORDER, "plain") + " 条，requires_new 写的 "
                + m.notifyCount(ORDER, "requires_new") + " 条");
    }

    // ---------------- 同一个监听器，连接池默认 autoCommit=false ----------------
    static void listenerNoAutoCommit() {
        log("\n== listener-noac：同样的付款，连接池配置 auto-commit=false（常见的调优项）==");
        try (ConfigurableApplicationContext ctx = start("spring.datasource.hikari.auto-commit=false")) {
            ds = ctx.getBean(DataSource.class);
            listener(ctx.getBean(PayService.class), ctx.getBean(TradeMapper.class));
        }
    }

    // ---------------- 打开 validateExistingTransaction ----------------
    static void validate() {
        log("\n== validate：事务管理器打开 validateExistingTransaction 再跑 iso ==");
        try (ConfigurableApplicationContext ctx = start("lab.validate=true")) {
            ds = ctx.getBean(DataSource.class);
            ctx.getBean(PayService.class).isolationPeek();
        } catch (Exception e) {
            log("   => 抛 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---------------- 监听器上挂普通 @Transactional ----------------
    static void badListener() {
        log("\n== bad-listener：@TransactionalEventListener 上加普通 @Transactional ==");
        try (ConfigurableApplicationContext ctx = start("lab.bad-listener=true")) {
            log("   => 启动成功（没有校验）");
        } catch (Exception e) {
            Throwable root = e; while (root.getCause() != null) root = root.getCause();
            log("   => 启动失败：" + root.getClass().getSimpleName() + ": " + root.getMessage().replaceAll(":.*", ": …"));
        }
    }
}
