package labs.kafka.kafka_idempotent_ordering;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 交易靶场 · J08 第 8、2 站：幂等消费与顺序消息。
 *
 * dup <none|split|same|offset>
 *   积分服务收到 evt-9527-paid，处理完、提交位点前“崩溃”（关掉消费者，不提交），
 *   同组新实例接手。四种写法：不去重 / 去重表和加积分分两个事务 / 同一个事务 / 位点和积分存在同一个库里。
 *
 * order <seq|pool|lanes|guard|nokey>
 *   1000 个订单，每单三条事件 PAID(ver 1) → SHIPPED(ver 2) → REFUNDED(ver 3)，一条接一条交错着发。
 *   seq：一条条处理；pool：丢给 8 个线程的池子；lanes：按订单号分到 8 条单线程“车道”；
 *   guard：照样丢池子，但只认版本号更大的事件；nokey：发的时候不带 key，消费端一条条处理。
 */
public class IdempotentOrdering {
    static final String BS = System.getProperty("bs", "localhost:19192");
    static final int ORDERS = Integer.getInteger("orders", 1000);

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "dup";
        String strategy = args.length > 1 ? args[1] : "same";
        if (mode.equals("dup")) dup(strategy);
        else order(strategy);
    }

    // ---------------------------------------------------------------- 公共
    static String newTopic(String prefix, int partitions) throws Exception {
        String t = prefix + "-" + Long.toString(System.currentTimeMillis(), 36);
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BS);
        try (Admin a = Admin.create(p)) {
            a.createTopics(List.of(new NewTopic(t, partitions, (short) 1))).all().get();
        }
        return t;
    }

    static KafkaProducer<String, String> producer() {
        Properties p = new Properties();   // 其余全是默认：acks=all、幂等开着、linger.ms=5
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BS);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(p);
    }

    static KafkaConsumer<String, String> consumer(String group) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BS);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");      // 处理完再手动提交
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(p);
    }

    // ---------------------------------------------------------------- dup：第 8 站
    static Connection db;

    static void dup(String strategy) throws Exception {
        db = DriverManager.getConnection("jdbc:h2:mem:points;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE points(user_id BIGINT PRIMARY KEY, total BIGINT)");
            s.execute("INSERT INTO points VALUES (1024, 0)");
            // 去重表：eventId 是主键，第二次插入会撞唯一键
            s.execute("CREATE TABLE consumed_event(event_id VARCHAR(64) PRIMARY KEY, at TIMESTAMP)");
            // 位点表：位点和业务数据存在同一个库里
            s.execute("CREATE TABLE consumer_offset(grp VARCHAR(64), topic VARCHAR(128), part INT, next_offset BIGINT, PRIMARY KEY(grp, topic, part))");
        }
        db.setAutoCommit(false);
        String topic = newTopic("order-events-dup-" + strategy, 6);
        String group = "points-svc";
        try (KafkaProducer<String, String> p = producer()) {
            p.send(new ProducerRecord<>(topic, "9527",
                    "{\"eventId\":\"evt-9527-paid\",\"type\":\"ORDER_PAID\",\"orderId\":9527,\"userId\":1024,\"amount\":798}")).get();
        }
        System.out.println("== dup/" + strategy + "：积分服务处理 evt-9527-paid，处理完、提交位点前崩溃");
        for (int inst = 1; inst <= 2; inst++) {
            boolean crash = inst == 1;
            KafkaConsumer<String, String> c = consumer(group);
            if (strategy.equals("offset")) c.subscribe(List.of(topic), new SeekFromDb(c, group));
            else c.subscribe(List.of(topic));
            ConsumerRecord<String, String> rec = null;
            long deadline = System.currentTimeMillis() + 8000;
            while (rec == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) rec = r;
            }
            if (rec == null) {
                System.out.printf("   实例 %d：8 秒内没收到消息（位点在库里，已经越过这条）%n", inst);
                c.close();
                continue;
            }
            String result = handle(strategy, group, rec, crash);
            System.out.printf("   实例 %d 收到 evt-9527-paid（分区 %d offset %d）：%s，积分 %+d，去重表 %d 行%n",
                    inst, rec.partition(), rec.offset(), result, total(), count("consumed_event"));
            if (crash) {
                c.close();     // 模拟崩溃：不提交位点就走了，同组下一个实例从上次提交的位置读
            } else {
                if (!strategy.equals("offset")) c.commitSync();
                c.close();
            }
        }
        System.out.printf("   结果：积分 %+d（应为 +798）%n", total());
    }

    /** 处理一条，crash=true 时在“该崩的地方”抛出去，模拟进程死掉。 */
    static String handle(String strategy, String group, ConsumerRecord<String, String> rec, boolean crash) throws SQLException {
        String eventId = "evt-9527-paid";
        switch (strategy) {
            case "none": {                       // 不去重：直接加
                addPoints(798); db.commit();
                return crash ? "加 798 分，崩溃" : "又加 798 分";
            }
            case "split": {                      // 去重表一个事务，加积分另一个事务
                if (!insertDedupe(eventId)) { db.rollback(); return "去重表里已有，跳过"; }
                db.commit();
                if (crash) return "去重表写好了，加积分前崩溃";
                addPoints(798); db.commit();
                return "加 798 分";
            }
            case "same": {                       // 去重 + 加积分同一个事务
                if (!insertDedupe(eventId)) { db.rollback(); return "撞唯一键，回滚，跳过"; }
                addPoints(798); db.commit();
                return crash ? "去重 + 加 798 分一起提交，然后崩溃" : "加 798 分";
            }
            case "offset": {                     // 加积分 + 写位点同一个事务，不用 Kafka 的提交
                addPoints(798);
                try (PreparedStatement ps = db.prepareStatement("MERGE INTO consumer_offset KEY(grp, topic, part) VALUES (?,?,?,?)")) {
                    ps.setString(1, group); ps.setString(2, rec.topic()); ps.setInt(3, rec.partition()); ps.setLong(4, rec.offset() + 1);
                    ps.executeUpdate();
                }
                db.commit();
                return crash ? "加 798 分 + 位点 " + (rec.offset() + 1) + " 一起提交，然后崩溃" : "加 798 分";
            }
            default: throw new IllegalArgumentException(strategy);
        }
    }

    static boolean insertDedupe(String eventId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO consumed_event VALUES (?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, eventId);
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {   // 唯一键冲突 = 处理过了
            return false;
        }
    }

    static void addPoints(long n) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("UPDATE points SET total = total + ? WHERE user_id = 1024")) {
            ps.setLong(1, n); ps.executeUpdate();
        }
    }

    static long total() throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT total FROM points WHERE user_id = 1024")) {
            r.next(); return r.getLong(1);
        }
    }

    static long count(String table) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next(); return r.getLong(1);
        }
    }

    /** 位点存在业务库里：分到分区时，从库里读出位置，seek 过去。 */
    static class SeekFromDb implements ConsumerRebalanceListener {
        final KafkaConsumer<String, String> c; final String group;
        SeekFromDb(KafkaConsumer<String, String> c, String group) { this.c = c; this.group = group; }
        public void onPartitionsRevoked(Collection<TopicPartition> parts) { }
        public void onPartitionsAssigned(Collection<TopicPartition> parts) {
            for (TopicPartition tp : parts) {
                try (PreparedStatement ps = db.prepareStatement("SELECT next_offset FROM consumer_offset WHERE grp=? AND topic=? AND part=?")) {
                    ps.setString(1, group); ps.setString(2, tp.topic()); ps.setInt(3, tp.partition());
                    try (ResultSet r = ps.executeQuery()) { if (r.next()) c.seek(tp, r.getLong(1)); }
                    db.commit();
                } catch (SQLException e) { throw new RuntimeException(e); }
            }
        }
    }

    // ---------------------------------------------------------------- order：第 2 站
    static final String[] TYPES = {"", "PAID", "SHIPPED", "REFUNDED"};

    static void order(String strategy) throws Exception {
        String topic = newTopic("order-flow-" + strategy, 6);
        boolean keyed = !strategy.equals("nokey");
        int total = ORDERS * 3;
        // 三条事件交错着发：第 t 轮发 订单 t 的 PAID、订单 t-1 的 SHIPPED、订单 t-2 的 REFUNDED
        try (KafkaProducer<String, String> p = producer()) {
            for (int t = 0; t < ORDERS + 2; t++) {
                for (int v = 1; v <= 3; v++) {
                    int id = 10000 + t - (v - 1);
                    if (id < 10000 || id >= 10000 + ORDERS) continue;
                    p.send(new ProducerRecord<>(topic, keyed ? String.valueOf(id) : null, id + "," + v));
                }
            }
            p.flush();
        }
        ConcurrentHashMap<Integer, Integer> ver = new ConcurrentHashMap<>();   // 订单当前的状态版本（模拟订单视图表）
        AtomicInteger done = new AtomicInteger(), backwards = new AtomicInteger(), stale = new AtomicInteger();
        boolean guard = strategy.equals("guard");
        Executor exec;
        ExecutorService pool = null; ExecutorService[] lanes = null;
        switch (strategy) {
            case "pool": case "guard": pool = Executors.newFixedThreadPool(8); ExecutorService fp = pool; exec = fp::execute; break;
            case "lanes":
                lanes = new ExecutorService[8];
                for (int i = 0; i < 8; i++) lanes[i] = Executors.newSingleThreadExecutor();
                exec = null; break;
            default: exec = Runnable::run;   // seq、nokey：在 poll 线程上一条条处理
        }
        Random rnd = new Random(42);
        long t0 = 0;
        try (KafkaConsumer<String, String> c = consumer("order-view-" + strategy)) {
            c.subscribe(List.of(topic));
            while (done.get() < total) {
                ConsumerRecords<String, String> rs = c.poll(Duration.ofMillis(200));
                if (!rs.isEmpty() && t0 == 0) t0 = System.nanoTime();
                for (ConsumerRecord<String, String> r : rs) {
                    String[] f = r.value().split(",");
                    int id = Integer.parseInt(f[0]), v = Integer.parseInt(f[1]);
                    long work = 200_000 + rnd.nextInt(1_300_000);        // 每条 0.2～1.5 ms 的“业务处理”
                    Runnable task = () -> {
                        LockSupport.parkNanos(work);
                        if (guard) {
                            // UPDATE order_view SET status=?, ver=? WHERE id=? AND ver < ?：旧事件更新 0 行
                            boolean[] applied = {false};
                            ver.compute(id, (k, cur) -> {
                                int cv = cur == null ? 0 : cur;
                                if (v > cv) { applied[0] = true; return v; }
                                return cv;
                            });
                            if (!applied[0]) stale.incrementAndGet();
                        } else {
                            ver.compute(id, (k, cur) -> {
                                if (cur != null && v < cur) backwards.incrementAndGet();  // 新状态被旧事件盖掉
                                return v;
                            });
                        }
                        done.incrementAndGet();
                    };
                    if (lanes != null) lanes[Math.floorMod(id, 8)].execute(task);   // 同一个订单永远进同一条车道
                    else exec.execute(task);
                }
            }
            c.commitSync();   // 全部处理完再提交（异步处理时怎么安全提交，见页面⑥）
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (pool != null) pool.shutdown();
        if (lanes != null) for (ExecutorService l : lanes) l.shutdown();
        long wrongFinal = ver.values().stream().filter(x -> x != 3).count();
        System.out.printf("== order/%-5s %d 单 × 3 条：用时 %5d ms；新状态被旧事件盖掉 %4d 次；最终不是 REFUNDED 的订单 %4d 个%s%n",
                strategy, ORDERS, ms, backwards.get(), wrongFinal, guard ? "；当成旧事件丢掉 " + stale.get() + " 条" : "");
    }
}
