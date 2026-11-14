package labs.rocketmq.rocketmq_tx_delay_order;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交易靶场里的三种“特殊消息”，对着一个单节点 RocketMQ 5.5.1 跑：
 *   tx     事务消息：订单服务改库 + 发“已支付”。正常提交、本地事务失败、进程死在两个位置，以及两种老写法。
 *   delay  定时消息：下单 10 秒（代替 30 分钟）没付就取消；顺带看 18 个延迟级别和超过 3 天的定时会怎样。
 *   order  顺序消息：1000 单 × 3 个事件，orderly | concurrent | rr（不按订单号选队列）三种消费方式。
 * NameServer 地址用 -Dns 指定，默认 127.0.0.1:19876；订单库是 H2 文件库，路径用 -Ddb 指定。
 */
public class SpecialMessages {
    static final String NS = System.getProperty("ns", "127.0.0.1:19876");
    static final String DB = "jdbc:h2:file:" + System.getProperty("db", "./data/orders")
            + ";MODE=MySQL;AUTO_SERVER=TRUE";
    static final long T0 = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "tx": tx(); break;
            case "txchild": txChild(Long.parseLong(args[1]), args[2], args[3]); break;
            case "delay": delay(); break;
            case "order": order(args[1]); break;
            default: throw new IllegalArgumentException(args[0]);
        }
        System.exit(0);
    }

    // ---------------------------------------------------------------- 订单库
    static Connection db() throws SQLException { return DriverManager.getConnection(DB, "sa", ""); }

    static void resetOrders(Connection c, long from, long to) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS orders(id BIGINT PRIMARY KEY, status VARCHAR(16))");
            s.execute("DELETE FROM orders");
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO orders VALUES(?, 'NEW')")) {
            for (long id = from; id <= to; id++) { p.setLong(1, id); p.addBatch(); }
            p.executeBatch();
        }
    }

    static String status(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT status FROM orders WHERE id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : "无此单"; }
        }
    }

    /** 本地事务：NEW → PAID。影响 0 行（单已经被取消）就抛异常。commit=false 时只改不提交。 */
    static Connection pay(long id, boolean commit) throws SQLException {
        Connection c = db();
        c.setAutoCommit(false);
        try (PreparedStatement p = c.prepareStatement("UPDATE orders SET status='PAID' WHERE id=? AND status='NEW'")) {
            p.setLong(1, id);
            if (p.executeUpdate() != 1) { c.rollback(); c.close(); throw new SQLException("订单 " + id + " 不是待支付"); }
        }
        if (commit) { c.commit(); c.close(); }
        return c;
    }

    static String t() { return String.format("+%5.1fs", (System.currentTimeMillis() - T0) / 1000.0); }
    static long idOf(MessageExt m) { return Long.parseLong(m.getKeys()); }
    static String body(MessageExt m) { return new String(m.getBody(), StandardCharsets.UTF_8); }

    static DefaultMQPushConsumer consumer(String group, String topic, String tag) throws Exception {
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(group);
        c.setNamesrvAddr(NS);
        c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        c.subscribe(topic, tag);  // 每次运行用一个新 tag，Broker 按 tag 过滤，读不到上一次运行的消息
        return c;
    }

    // ---------------------------------------------------------------- tx：事务消息
    static final String TX_GROUP = "order-tx-pg";

    static void tx() throws Exception {
        String tag = "r" + T0;
        Connection main = db();  // 父进程一直开着库，H2 的 AUTO_SERVER 让子进程通过它访问同一个文件
        resetOrders(main, 9527, 9532);

        // 积分服务：只消费 order-paid
        Map<Long, String> got = new ConcurrentHashMap<>();
        DefaultMQPushConsumer points = consumer("points-cg-" + T0, "order-paid", tag);
        points.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                long id = idOf(m);
                String st;
                try (Connection c = db()) { st = status(c, id); } catch (SQLException e) { st = "?"; }
                got.put(id, t() + "（此刻库里是 " + st + "）");
                System.out.printf("%s 积分服务收到 %s，库里订单状态 %s%n", t(), body(m), st);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        points.start();

        // 订单服务的另一个实例：同一个生产者组，负责回答 Broker 的回查
        AtomicInteger checks = new AtomicInteger();
        TransactionMQProducer producer = new TransactionMQProducer(TX_GROUP);
        producer.setNamesrvAddr(NS);
        producer.setTransactionListener(new TransactionListener() {
            @Override public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                long id = Long.parseLong(msg.getKeys());
                try {
                    pay(id, true);
                    return LocalTransactionState.COMMIT_MESSAGE;
                } catch (SQLException e) {
                    System.out.printf("%s 订单 %d 本地事务失败：%s → 返回 ROLLBACK%n", t(), id, e.getMessage());
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
            }
            @Override public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                if (!tag.equals(msg.getTags())) return LocalTransactionState.ROLLBACK_MESSAGE;  // 以前运行留下的半消息
                long id = idOf(msg);
                String st;
                try (Connection c = db()) { st = status(c, id); } catch (SQLException e) { return LocalTransactionState.UNKNOW; }
                LocalTransactionState r = "PAID".equals(st) ? LocalTransactionState.COMMIT_MESSAGE
                        : System.currentTimeMillis() - msg.getBornTimestamp() > 10_000 ? LocalTransactionState.ROLLBACK_MESSAGE
                        : LocalTransactionState.UNKNOW;
                checks.incrementAndGet();
                System.out.printf("%s Broker 回查订单 %d（半消息已存在 %.1fs，第 %s 次）：库里 %s → %s%n", t(), id,
                        (System.currentTimeMillis() - msg.getBornTimestamp()) / 1000.0,
                        msg.getProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES), st, r);
                return r;
            }
        });
        producer.start();

        System.out.printf("%s 9527 正常：发半消息 → 本地事务提交 → COMMIT%n", t());
        producer.sendMessageInTransaction(msg("order-paid", tag, 9527, "PAID:9527"), null);
        try (Statement s = main.createStatement()) { s.execute("UPDATE orders SET status='CANCELLED' WHERE id=9528"); }
        System.out.printf("%s 9528 本地事务失败：这单已被取消%n", t());
        producer.sendMessageInTransaction(msg("order-paid", tag, 9528, "PAID:9528"), null);

        Map<Long, Long> killedAt = new ConcurrentHashMap<>();
        String[][] kids = {
            {"9529", "half-commit-die", "半消息发出 → 本地事务已提交 → 还没告诉 Broker 就被杀"},
            {"9530", "half-die", "半消息发出 → 本地事务还没提交就被杀"},
            {"9531", "old-commit-die", "老写法：先提交库，再发普通消息，中间被杀"},
            {"9532", "old-send-die", "老写法：先发普通消息，再提交库，中间被杀"},
        };
        for (String[] k : kids) {
            System.out.printf("%s %s %s%n", t(), k[0], k[2]);
            Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                    "-cp", System.getProperty("java.class.path"), "-Dns=" + NS, "-Ddb=" + System.getProperty("db", "./data/orders"),
                    "-Duser.home=" + System.getProperty("user.home"),
                    SpecialMessages.class.getName(), "txchild", k[0], k[1], tag).inheritIO().start();
            p.waitFor();
            killedAt.put(Long.parseLong(k[0]), System.currentTimeMillis());
            System.out.printf("%s 子进程退出码 %d（halt，等同 kill -9）%n", t(), p.exitValue());
        }

        // 等回查：半消息至少 6 秒（transactionTimeOut）才会被查，检查任务 30 秒一轮
        long deadline = System.currentTimeMillis() + 75_000;
        while (System.currentTimeMillis() < deadline && !(got.containsKey(9529L) && checks.get() >= 2)) Thread.sleep(500);
        Thread.sleep(3000);

        System.out.println("\n== 结果 ==");
        System.out.println("订单  库里最终状态  积分服务收到“已支付”了吗");
        for (long id = 9527; id <= 9532; id++) {
            System.out.printf("%d  %-10s  %s%n", id, status(main, id), got.getOrDefault(id, "没收到"));
        }
        System.out.printf("回查次数：%d%n", checks.get());
        producer.shutdown();
        points.shutdown();
    }

    static Message msg(String topic, String tag, long id, String body) {
        Message m = new Message(topic, tag, String.valueOf(id), body.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    /** 子进程：在指定的位置用 halt 结束自己，不跑 shutdown hook，等同 kill -9。 */
    static void txChild(long id, String mode, String tag) throws Exception {
        String pre = "  [子进程 " + id + "] ";
        if (mode.startsWith("old")) {
            DefaultMQProducer p = new DefaultMQProducer("order-plain-pg");
            p.setNamesrvAddr(NS);
            p.start();
            if (mode.equals("old-commit-die")) {
                pay(id, true);
                System.out.println(pre + "库已提交 PAID，正要发消息……");
            } else {
                SendResult r = p.send(msg("order-paid", tag, id, "PAID:" + id));
                System.out.println(pre + "普通消息已发出 " + r.getSendStatus() + "，正要提交库……");
                pay(id, false);
            }
            Runtime.getRuntime().halt(9);
        }
        TransactionMQProducer p = new TransactionMQProducer(TX_GROUP);
        p.setNamesrvAddr(NS);
        p.setTransactionListener(new TransactionListener() {
            @Override public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                try {
                    System.out.println(pre + "半消息已写入 Broker，开始本地事务");
                    pay(id, mode.equals("half-commit-die"));
                    System.out.println(pre + (mode.equals("half-commit-die") ? "库已提交 PAID，正要 COMMIT……" : "UPDATE 已执行、没提交……"));
                } catch (SQLException e) { e.printStackTrace(); }
                Runtime.getRuntime().halt(9);
                return null;
            }
            @Override public LocalTransactionState checkLocalTransaction(MessageExt msg) { return LocalTransactionState.UNKNOW; }
        });
        p.start();
        p.sendMessageInTransaction(msg("order-paid", tag, id, "PAID:" + id), null);
    }

    // ---------------------------------------------------------------- delay：定时消息
    static void delay() throws Exception {
        String tag = "r" + T0;
        int n = 300;
        long delayMs = 10_000;  // 代替“30 分钟未支付自动取消”
        Connection main = db();
        resetOrders(main, 1, n);

        List<Long> lateTimer = Collections.synchronizedList(new ArrayList<>());
        List<Long> lateLevel = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger cancelled = new AtomicInteger(), skipped = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2 * n);
        DefaultMQPushConsumer c = consumer("cancel-cg-" + T0, "order-cancel-check", tag);
        c.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                long due = Long.parseLong(m.getUserProperty("due"));
                long late = System.currentTimeMillis() - due;
                if (m.getUserProperty("level") != null) { lateLevel.add(late); done.countDown(); continue; }
                lateTimer.add(late);
                try (Connection x = db(); PreparedStatement p = x.prepareStatement(
                        "UPDATE orders SET status='CANCELLED' WHERE id=? AND status='NEW'")) {
                    p.setLong(1, idOf(m));
                    if (p.executeUpdate() == 1) cancelled.incrementAndGet(); else skipped.incrementAndGet();
                } catch (SQLException e) { return ConsumeConcurrentlyStatus.RECONSUME_LATER; }
                done.countDown();
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        c.start();

        DefaultMQProducer p = new DefaultMQProducer("order-pg");
        p.setNamesrvAddr(NS);
        p.start();
        for (long id = 1; id <= n; id++) {
            long due = System.currentTimeMillis() + delayMs;
            Message m = msg("order-cancel-check", tag, id, "CHECK:" + id);
            m.setDeliverTimeMs(due);  // 5.x 定时消息：任意时间点，精度 timerPrecisionMs（默认 1 秒）
            m.putUserProperty("due", String.valueOf(due));
            p.send(m);
            Message l = msg("order-cancel-check", tag, id, "LEVEL:" + id);
            l.setDelayTimeLevel(3);  // 4.x 起就有的 18 个级别，第 3 级 = 10s
            l.putUserProperty("due", String.valueOf(System.currentTimeMillis() + delayMs));
            l.putUserProperty("level", "3");
            p.send(l);
        }
        System.out.printf("%s 已发 %d 条定时消息（10 秒后）和 %d 条第 3 级延迟消息%n", t(), n, n);
        Thread.sleep(3000);
        int paid = 0;
        for (long id = 3; id <= n; id += 3) { pay(id, true); paid++; }
        System.out.printf("%s 其中 %d 单在 3 秒时付了款%n", t(), paid);

        try {
            Message far = msg("order-cancel-check", tag, 0, "FAR");
            far.setDeliverTimeMs(System.currentTimeMillis() + 4L * 24 * 3600 * 1000);
            p.send(far);
            System.out.println("4 天后的定时消息：发送成功");
        } catch (Exception e) {
            System.out.println("4 天后的定时消息：被拒绝 —— " + e.getMessage().split("\n")[0]);
        }

        done.await(40, TimeUnit.SECONDS);
        System.out.printf("%n== 结果 ==%n取消 %d 单，跳过（已付款）%d 单%n", cancelled.get(), skipped.get());
        System.out.println("实际投递比预定时间晚多少毫秒：");
        System.out.println("  定时消息 setDeliverTimeMs  " + pct(lateTimer));
        System.out.println("  延迟级别 setDelayTimeLevel " + pct(lateLevel));
        p.shutdown();
        c.shutdown();
    }

    static String pct(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        if (s.isEmpty()) return "（没收到）";
        return String.format("n=%d 最早 %d · 中位 %d · p99 %d · 最晚 %d", s.size(), s.get(0), s.get(s.size() / 2),
                s.get((int) (s.size() * 0.99)), s.get(s.size() - 1));
    }

    // ---------------------------------------------------------------- order：顺序消息
    static final String[] EV = {"PAID", "SHIPPED", "REFUNDED"};

    static void order(String mode) throws Exception {
        String tag = "r" + T0;
        int orders = 1000;
        DefaultMQProducer p = new DefaultMQProducer("order-pg");
        p.setNamesrvAddr(NS);
        p.start();
        // 每 10 单一批：10 个 PAID、10 个 SHIPPED、10 个 REFUNDED，同一单的三个事件隔 10 条
        for (int b = 0; b < orders; b += 10) {
            for (int e = 0; e < 3; e++) {
                for (long id = 10000 + b; id < 10000 + b + 10; id++) {
                    Message m = msg("order-status", tag, id, EV[e] + ":" + id);
                    m.putUserProperty("rank", String.valueOf(e));
                    if (mode.equals("rr")) p.send(m);  // 不指定队列：轮着发到 8 个队列
                    else p.send(m, (mqs, msg, arg) -> mqs.get((int) ((Long) arg % mqs.size())), id);
                }
            }
        }
        p.shutdown();

        Map<Long, Integer> state = new ConcurrentHashMap<>();
        AtomicInteger backwards = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(orders * 3);
        long[] first = {0};
        DefaultMQPushConsumer c = consumer("status-cg-" + mode + "-" + T0, "order-status", tag);
        java.util.function.Consumer<MessageExt> apply = m -> {
            if (first[0] == 0) first[0] = System.currentTimeMillis();
            try { Thread.sleep(1); } catch (InterruptedException ignored) { }  // 代表一次写库
            int r = Integer.parseInt(m.getUserProperty("rank"));
            Integer old = state.put(idOf(m), r);
            if (old != null && old > r) backwards.incrementAndGet();
            done.countDown();
        };
        if (mode.equals("concurrent")) {
            c.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
                msgs.forEach(apply);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
        } else {
            c.registerMessageListener((MessageListenerOrderly) (msgs, ctx) -> {
                msgs.forEach(apply);
                return ConsumeOrderlyStatus.SUCCESS;
            });
        }
        long t0 = System.currentTimeMillis();
        c.start();
        done.await(60, TimeUnit.SECONDS);
        long end = System.currentTimeMillis();
        long wrong = state.values().stream().filter(r -> r != 2).count();
        System.out.printf("order %-10s 3000 条从第一条到最后一条 %d ms（启动到结束 %d ms），最终不是 REFUNDED 的 %d 单，" +
                "新状态被旧事件盖掉 %d 次%n", mode, end - first[0], end - t0, wrong, backwards.get());
        c.shutdown();
    }
}
