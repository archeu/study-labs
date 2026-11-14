package labs.kafka.kafka_backlog_drain;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 交易靶场 · 大促 20:00，notify-svc（发“支付成功”短信）积压 100 万条。
 *
 *   fill  <topic> <条数> <时间跨度分钟>   往 topic 灌积压：key = 订单号，时间戳均匀铺在过去 N 分钟
 *   drain k=v ...                       起若干个 notify-svc 实例消费一段时间，量出每秒发了多少条
 *   relay <源 topic> <目标 topic>        把积压原样（同 key）搬到分区更多的 topic
 *
 * drain 的参数（都有默认值）：
 *   topic=order-events  group=随机  inst=实例数  lanes=每个实例里按 key 分几条车道并行
 *   smsMs=每条短信耗时  smsLimit=短信平台每秒上限（0=不限）  secs=跑几秒  win=按最后几秒算速度
 *   protocol=classic|consumer（KIP-848）  share=true 用共享组（KIP-932）  mpr=max.poll.records
 *   mpi=max.poll.interval.ms  skipMin=创建超过 N 分钟的通知不发短信，直接跳过（0=不跳）
 */
public class Backlog {
    static final String BS = System.getProperty("bs", "localhost:19192");
    static final long T0 = System.nanoTime();
    static double now() { return (System.nanoTime() - T0) / 1e9; }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "fill": fill(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3])); break;
            case "relay": relay(a[1], a[2]); break;
            case "drain": drain(parse(Arrays.copyOfRange(a, 1, a.length))); break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }

    static Map<String, String> parse(String[] kv) {
        Map<String, String> m = new HashMap<>();
        for (String s : kv) { int i = s.indexOf('='); m.put(s.substring(0, i), s.substring(i + 1)); }
        return m;
    }

    // ---------- fill：灌 100 万条“支付成功” ----------
    static void fill(String topic, int n, int spanMin) throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", BS);
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        p.put("linger.ms", "20"); p.put("batch.size", "262144"); p.put("compression.type", "lz4");
        long end = System.currentTimeMillis(), start = end - spanMin * 60_000L;
        long t = System.nanoTime();
        try (KafkaProducer<String, String> pr = new KafkaProducer<>(p)) {
            for (int i = 0; i < n; i++) {
                long orderId = 100_000 + i;                     // 9527 也在里面：第 0 条换成它
                if (i == 0) orderId = 9527;
                long ts = start + (long) ((end - start) * (i / (double) n));
                pr.send(new ProducerRecord<>(topic, null, ts, String.valueOf(orderId),
                        "PAID|" + orderId + "|138" + String.format("%08d", orderId % 100_000_000)));
            }
            pr.flush();
        }
        System.out.printf("fill: %d 条写进 %s，用了 %.1f s，时间戳铺在过去 %d 分钟%n",
                n, topic, (System.nanoTime() - t) / 1e9, spanMin);
    }

    // ---------- relay：搬到分区更多的 topic，key 不变 ----------
    static void relay(String from, String to) throws Exception {
        Properties c = consumerProps("relay-" + System.currentTimeMillis(), "classic", 2000, 300_000);
        Properties p = new Properties();
        p.put("bootstrap.servers", BS);
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        p.put("linger.ms", "20"); p.put("batch.size", "262144"); p.put("compression.type", "lz4");
        long n = 0, t = System.nanoTime();
        try (KafkaConsumer<String, String> con = new KafkaConsumer<>(c);
             KafkaProducer<String, String> pr = new KafkaProducer<>(p)) {
            List<TopicPartition> tps = new ArrayList<>();
            con.partitionsFor(from).forEach(pi -> tps.add(new TopicPartition(from, pi.partition())));
            con.assign(tps); con.seekToBeginning(tps);
            Map<TopicPartition, Long> endOff = con.endOffsets(tps);
            while (true) {
                boolean done = true;
                for (TopicPartition tp : tps) if (con.position(tp) < endOff.get(tp)) done = false;
                if (done) break;
                for (ConsumerRecord<String, String> r : con.poll(Duration.ofMillis(500))) {
                    // 同一个 key 在新 topic 里还落在同一个分区：同一订单的通知仍然有序
                    pr.send(new ProducerRecord<>(to, null, r.timestamp(), r.key(), r.value()));
                    n++;
                }
            }
            pr.flush();   // 先确认新 topic 写成功，再提交旧 topic 的位点
            con.commitSync();
        }
        double s = (System.nanoTime() - t) / 1e9;
        System.out.printf("relay: %s → %s 搬了 %d 条，用了 %.1f s（%.0f 条/s）%n", from, to, n, s, n / s);
    }

    // ---------- drain：notify-svc 发短信 ----------
    static final AtomicLong sent = new AtomicLong(), skipped = new AtomicLong(), dup = new AtomicLong();
    static final AtomicLong revokes = new AtomicLong(), lost = new AtomicLong(), commitFail = new AtomicLong();
    static final ConcurrentHashMap<Long, Boolean> seen = new ConcurrentHashMap<>();
    static Limiter limiter;
    static volatile boolean running = true;

    static void drain(Map<String, String> o) throws Exception {
        String topic = o.getOrDefault("topic", "order-events");
        String group = o.getOrDefault("group", "notify-svc-" + System.currentTimeMillis() % 100000);
        int inst = Integer.parseInt(o.getOrDefault("inst", "3"));
        int lanes = Integer.parseInt(o.getOrDefault("lanes", "1"));
        int smsMs = Integer.parseInt(o.getOrDefault("smsMs", "20"));
        int smsLimit = Integer.parseInt(o.getOrDefault("smsLimit", "0"));
        int secs = Integer.parseInt(o.getOrDefault("secs", "20"));
        int win = Integer.parseInt(o.getOrDefault("win", "10"));
        String protocol = o.getOrDefault("protocol", "classic");
        boolean share = Boolean.parseBoolean(o.getOrDefault("share", "false"));
        int mpr = Integer.parseInt(o.getOrDefault("mpr", "100"));
        int mpi = Integer.parseInt(o.getOrDefault("mpi", "300000"));
        int skipMin = Integer.parseInt(o.getOrDefault("skipMin", "0"));
        limiter = smsLimit > 0 ? new Limiter(smsLimit) : null;
        long staleBefore = skipMin > 0 ? System.currentTimeMillis() - skipMin * 60_000L : Long.MIN_VALUE;

        System.out.printf("drain: topic=%s group=%s 实例=%d 车道/实例=%d 短信=%dms/条 平台上限=%s 协议=%s max.poll.records=%d max.poll.interval.ms=%d%s%n",
                topic, group, inst, lanes, smsMs, smsLimit > 0 ? smsLimit + "/s" : "不限",
                share ? "share(KIP-932)" : protocol, mpr, mpi, skipMin > 0 ? " 跳过超过 " + skipMin + " 分钟的" : "");
        if (share) setShareReset(group);

        ExecutorService pool = Executors.newFixedThreadPool(inst);
        Map<Integer, Set<Integer>> owned = new ConcurrentHashMap<>();
        for (int i = 1; i <= inst; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    if (share) runShare(id, topic, group, smsMs, mpr, staleBefore);
                    else runClassic(id, topic, group, protocol, lanes, smsMs, mpr, mpi, staleBefore, owned);
                } catch (Throwable e) { System.out.println("  实例" + id + " 异常：" + e); }
                return null;
            });
        }
        long[] at = new long[secs + 1];
        for (int s = 1; s <= secs; s++) {
            Thread.sleep(1000);
            at[s] = sent.get() + skipped.get();
            if (s % 5 == 0) System.out.printf("  t=%2ds 已发 %d 条，跳过 %d 条，重复 %d 条，分区被收回 %d 次%n",
                    s, sent.get(), skipped.get(), dup.get(), revokes.get());
        }
        double rate = (at[secs] - at[secs - win]) / (double) win;   // 只算最后 win 秒：避开首次加入组的那几秒
        Map<Integer, Integer> parts = new TreeMap<>();
        owned.forEach((k, v) -> parts.put(k, v.size()));
        running = false;
        pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS);
        System.out.printf("结果: 稳定后 %.0f 条/s → 消化 100 万条约 %.1f 分钟 | 结束时各实例分到的分区数 %s | 重复发送 %d | 分区被收回 %d 次 | 提交失败 %d 次%n",
                rate, 1_000_000 / rate / 60, share ? "（共享组不分配分区）" : parts.toString(),
                dup.get(), revokes.get(), commitFail.get());
    }

    static Properties consumerProps(String group, String protocol, int mpr, int mpi) {
        Properties p = new Properties();
        p.put("bootstrap.servers", BS);
        p.put("group.id", group);
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        p.put("enable.auto.commit", "false");
        p.put("auto.offset.reset", "earliest");
        p.put("max.poll.records", String.valueOf(mpr));
        p.put("max.poll.interval.ms", String.valueOf(mpi));
        p.put("group.protocol", protocol);   // classic（默认）或 consumer（KIP-848）
        return p;
    }

    static void runClassic(int id, String topic, String group, String protocol, int lanes, int smsMs, int mpr, int mpi,
                           long staleBefore, Map<Integer, Set<Integer>> owned) throws Exception {
        Set<Integer> mine = ConcurrentHashMap.newKeySet();
        owned.put(id, mine);
        ExecutorService[] lane = new ExecutorService[lanes];
        for (int i = 0; i < lanes; i++) lane[i] = Executors.newSingleThreadExecutor();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProps(group, protocol, mpr, mpi))) {
            c.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                public void onPartitionsRevoked(Collection<TopicPartition> ps) {
                    if (ps.isEmpty() || !running) return;   // 结束时 close() 交出分区不算
                    revokes.incrementAndGet();
                    ps.forEach(tp -> mine.remove(tp.partition()));
                    System.out.printf("  t=%5.1fs 实例%d 交出分区 %s%n", now(), id, parts(ps));
                }
                public void onPartitionsLost(Collection<TopicPartition> ps) {
                    if (ps.isEmpty()) return;
                    lost.incrementAndGet(); revokes.incrementAndGet();
                    ps.forEach(tp -> mine.remove(tp.partition()));
                    System.out.printf("  t=%5.1fs 实例%d 失去分区 %s（已被踢出组）%n", now(), id, parts(ps));
                }
                public void onPartitionsAssigned(Collection<TopicPartition> ps) {
                    ps.forEach(tp -> mine.add(tp.partition()));
                    if (!ps.isEmpty()) System.out.printf("  t=%5.1fs 实例%d 分到分区 %s%n", now(), id, parts(ps));
                }
            });
            while (running) {
                ConsumerRecords<String, String> rs = c.poll(Duration.ofMillis(200));
                if (rs.isEmpty()) continue;
                if (lanes == 1) {
                    for (ConsumerRecord<String, String> r : rs) handle(r, smsMs, staleBefore);
                } else {
                    // 同一个订单（key）总进同一条车道：同一订单的短信仍按顺序发；整批做完才提交
                    List<Future<?>> fs = new ArrayList<>();
                    Map<Integer, List<ConsumerRecord<String, String>>> byLane = new HashMap<>();
                    for (ConsumerRecord<String, String> r : rs)
                        byLane.computeIfAbsent(Math.floorMod(r.key().hashCode(), lanes), k -> new ArrayList<>()).add(r);
                    byLane.forEach((l, list) -> fs.add(lane[l].submit(() -> { list.forEach(r -> handle(r, smsMs, staleBefore)); return null; })));
                    for (Future<?> f : fs) f.get();
                }
                try { c.commitSync(); }
                catch (CommitFailedException | org.apache.kafka.common.errors.RebalanceInProgressException e) {
                    commitFail.incrementAndGet();
                    System.out.printf("  t=%5.1fs 实例%d 提交失败：%s%n", now(), id, e.getClass().getSimpleName());
                }
            }
        } finally { for (ExecutorService e : lane) e.shutdownNow(); }
    }

    static void runShare(int id, String topic, String group, int smsMs, int mpr, long staleBefore) {
        Properties p = new Properties();
        p.put("bootstrap.servers", BS);
        p.put("group.id", group);
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        p.put("max.poll.records", String.valueOf(mpr));
        try (KafkaShareConsumer<String, String> c = new KafkaShareConsumer<>(p)) {
            c.subscribe(List.of(topic));
            while (running) {
                ConsumerRecords<String, String> rs = c.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> r : rs) handle(r, smsMs, staleBefore);
                c.commitSync();   // 隐式确认：这批全部 ACCEPT
            }
        }
    }

    static void handle(ConsumerRecord<String, String> r, int smsMs, long staleBefore) {
        if (seen.put(((long) r.partition() << 40) | r.offset(), Boolean.TRUE) != null) dup.incrementAndGet();
        if (r.timestamp() < staleBefore) { skipped.incrementAndGet(); return; }   // 过期的通知：不发短信
        if (limiter != null) limiter.acquire();
        sleepMs(smsMs);                                                          // 调短信平台
        sent.incrementAndGet();
    }

    static String parts(Collection<TopicPartition> ps) {
        StringBuilder sb = new StringBuilder();
        ps.stream().map(TopicPartition::partition).sorted().forEach(x -> sb.append(sb.length() == 0 ? "" : ",").append(x));
        return "[" + sb + "]";
    }

    static void sleepMs(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static void setShareReset(String group) throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", BS);
        try (Admin a = Admin.create(p)) {   // 共享组默认从最新开始读，积压演练要从头读
            ConfigResource r = new ConfigResource(ConfigResource.Type.GROUP, group);
            a.incrementalAlterConfigs(Map.of(r, List.of(new AlterConfigOp(
                    new ConfigEntry("share.auto.offset.reset", "earliest"), AlterConfigOp.OpType.SET)))).all().get();
        }
    }

    /** 短信平台的限流：全局每秒最多 n 条，所有实例共用一个。 */
    static final class Limiter {
        final long intervalNs; long next = System.nanoTime();
        Limiter(int perSec) { intervalNs = 1_000_000_000L / perSec; }
        void acquire() {
            long wait;
            synchronized (this) { long t = System.nanoTime(); if (next < t) next = t; wait = next - t; next += intervalNs; }
            if (wait > 0) LockSupport.parkNanos(wait);
        }
    }
}
