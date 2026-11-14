package labs.kafka.kafka_tiered_compact;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 交易靶场 · J08 第 11 站：消息和位点各自过期以后，积分服务回来还能不能补上。
 * <pre>
 *   outage   积分服务停 8 天：order-events（保留 7 天）vs order-events-tiered（本地 1 天，总共 30 天）
 *   compact  order-state 开日志压缩，每个订单只留最新状态
 *   down     远端存储（这里是本机一个目录）不可用时，谁受影响
 * </pre>
 * 参数：bs=… logDir=… remoteDir=…（run.sh 传进来）。时间缩小：1 天 = 60/7 秒。
 */
public class Retention {
    static final long DAY = 60_000 / 7;               // ≈ 8571 ms
    static final int PARTS = 6;                        // 和全景一致：order-events 6 个分区
    static final String PLAIN = "order-events", TIERED = "order-events-tiered", STATE = "order-state";
    static String bs; static File logDir, remoteDir;

    public static void main(String[] a) throws Exception {
        Map<String, String> p = new HashMap<>();
        for (String s : a) if (s.contains("=")) p.put(s.substring(0, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
        bs = p.getOrDefault("bs", "localhost:19292");
        logDir = new File(p.getOrDefault("logDir", "data/log"));
        remoteDir = new File(p.getOrDefault("remoteDir", "data/remote"));
        switch (a[0]) {
            case "outage" -> outage();
            case "compact" -> compact();
            case "down" -> down();
            default -> System.out.println("outage | compact | down");
        }
    }

    // ---------------------------------------------------------------- outage
    static void outage() throws Exception {
        try (Admin admin = admin()) {
            recreate(admin, new NewTopic(PLAIN, PARTS, (short) 1).configs(Map.of(
                    "retention.ms", "" + 7 * DAY, "segment.ms", "" + DAY)));
            recreate(admin, new NewTopic(TIERED, PARTS, (short) 1).configs(Map.of(
                    "remote.storage.enable", "true",
                    "local.retention.ms", "" + DAY, "retention.ms", "" + 30 * DAY, "segment.ms", "" + DAY)));
            System.out.printf("时间比例：1 天 = %d ms。两个 topic 各 %d 分区，订单服务每 50 ms 发一条“支付成功”（同时写两个 topic）%n", DAY, PARTS);
            System.out.println("order-events：retention.ms = 7 天；order-events-tiered：local.retention.ms = 1 天，retention.ms = 30 天；segment.ms 都是 1 天");

            AtomicBoolean run = new AtomicBoolean(true);
            AtomicLong sent = new AtomicLong();
            Thread prod = new Thread(() -> produceLoop(run, sent), "order-svc");
            prod.start();

            // 第 0 天：积分服务正常消费 1 天，然后宕机
            Map<String, Map<TopicPartition, Long>> committed = new HashMap<>();
            for (String t : List.of(PLAIN, TIERED)) committed.put(t, null);
            long t0 = System.currentTimeMillis();
            KafkaConsumer<String, String> cp = consumer("points-svc-plain", "earliest");
            KafkaConsumer<String, String> ct = consumer("points-svc-tiered", "earliest");
            cp.subscribe(List.of(PLAIN)); ct.subscribe(List.of(TIERED));
            long readP = 0, readT = 0;
            while (System.currentTimeMillis() - t0 < DAY) {
                readP += cp.poll(Duration.ofMillis(200)).count();
                readT += ct.poll(Duration.ofMillis(200)).count();
            }
            cp.commitSync(); ct.commitSync();
            committed.put(PLAIN, positions(cp)); committed.put(TIERED, positions(ct));
            cp.close(); ct.close();
            long stopAt = System.currentTimeMillis();
            System.out.printf("%n第 1 天末：积分服务读了 %d / %d 条，提交位点后宕机（组里没人了）%n", readP, readT);
            System.out.println("天 | order-events 本地段 | 本地最早 offset | order-events-tiered 本地段 | 远端段 | 本地最早 / 全部最早 | 位点还在吗（plain / tiered）");

            for (int d = 1; d <= 9; d++) {
                double at = d == 9 ? 8.5 : d;                     // 宕机 8 天半：第 9 天中午修好
                Thread.sleep(Math.max(0, stopAt + (long) (at * DAY) - System.currentTimeMillis()));
                System.out.printf("%4s | %3d 个 %7s | %6d | %3d 个 %7s | %3d 个 | %6d / %d | %s / %s%n", d == 9 ? "9.5" : "" + (d + 1),
                        localSegs(PLAIN), kb(localBytes(PLAIN)), sum(earliest(admin, PLAIN, OffsetSpec.earliest())),
                        localSegs(TIERED), kb(localBytes(TIERED)), remoteSegs(TIERED),
                        sum(earliest(admin, TIERED, OffsetSpec.earliestLocal())), sum(earliest(admin, TIERED, OffsetSpec.earliest())),
                        groupHasOffsets(admin, "points-svc-plain") ? "在" : "没了",
                        groupHasOffsets(admin, "points-svc-tiered") ? "在" : "没了");
            }
            run.set(false); prod.join();
            Map<TopicPartition, Long> endP = earliest(admin, PLAIN, OffsetSpec.latest());
            Map<TopicPartition, Long> endT = earliest(admin, TIERED, OffsetSpec.latest());
            long missedP = sum(endP) - sum(committed.get(PLAIN)), missedT = sum(endT) - sum(committed.get(TIERED));
            System.out.printf("%n第 9.5 天：积分服务修好重启。宕机期间新增 %d / %d 条（订单服务共发了 %d 条）%n", missedP, missedT, sent.get());

            // ① 普通 topic，默认 latest：位点没了，直接跳到末尾
            long got = drainFrom(PLAIN, "points-svc-plain", "latest", null, endP);
            System.out.printf("① order-events，auto.offset.reset=latest：补上 %d 条，丢 %d 条%n", got, missedP - got);
            // ② 普通 topic，改 earliest：从本地最早的一条开始，可宕机头几天的段已经整段删了
            Map<TopicPartition, Long> startP = earliest(admin, PLAIN, OffsetSpec.earliest());
            long lostE = 0;
            for (var e : committed.get(PLAIN).entrySet()) lostE += Math.max(0, startP.get(e.getKey()) - e.getValue());
            got = drainFrom(PLAIN, "points-svc-plain-e", "earliest", null, endP);
            System.out.printf("② order-events，auto.offset.reset=earliest：读了 %d 条（含宕机前已处理过的，靠去重表跳过），宕机期间的丢 %d 条%n", got, lostE);
            // ③ 分层存储 topic，位点也没了，但消息还在：按宕机时间点找回位点
            long t1 = System.currentTimeMillis();
            got = drainFrom(TIERED, "points-svc-tiered", "latest", stopAt - 1000, endT);
            System.out.printf("③ order-events-tiered，按宕机前 1 秒的时间戳 offsetsForTimes 找位点：补上 %d 条（宕机期间 %d 条），用时 %d ms%n",
                    got, missedT, System.currentTimeMillis() - t1);
            System.out.printf("   这时 order-events-tiered 本地只剩 %d 个段，远端 %d 个段；本地最早 offset 合计 %d，全部最早 %d%n",
                    localSegs(TIERED), remoteSegs(TIERED), sum(earliest(admin, TIERED, OffsetSpec.earliestLocal())),
                    sum(earliest(admin, TIERED, OffsetSpec.earliest())));
        }
    }

    static void produceLoop(AtomicBoolean run, AtomicLong sent) {
        try (KafkaProducer<String, String> p = producer()) {
            long id = 100000;
            while (run.get()) {
                String k = "" + id++, v = "evt-" + k + "-paid,points=+" + (id % 900 + 100);
                p.send(new ProducerRecord<>(PLAIN, k, v));
                p.send(new ProducerRecord<>(TIERED, k, v));
                sent.incrementAndGet();
                Thread.sleep(50);
            }
        } catch (InterruptedException ignored) { }
    }

    /** 新起一个积分服务实例，读到 end 为止（最多 60 秒），返回读到的条数。fromTs 不为空时按时间戳找位点。 */
    static long drainFrom(String topic, String group, String reset, Long fromTs, Map<TopicPartition, Long> end) {
        try (KafkaConsumer<String, String> c = consumer(group, reset)) {
            List<TopicPartition> tps = new ArrayList<>(end.keySet());
            c.assign(tps);
            Map<TopicPartition, OffsetAndMetadata> com = c.committed(new HashSet<>(tps));
            if (fromTs != null) {
                Map<TopicPartition, Long> q = tps.stream().collect(Collectors.toMap(tp -> tp, tp -> fromTs));
                for (var e : c.offsetsForTimes(q).entrySet()) {
                    OffsetAndTimestamp o = e.getValue();
                    if (o != null) c.seek(e.getKey(), o.offset()); else c.seekToEnd(List.of(e.getKey()));
                }
            }
            if (com.values().stream().anyMatch(Objects::nonNull)) System.out.println("   （组里居然还有位点：" + com + "）");
            long n = 0, deadline = System.currentTimeMillis() + 60_000, firstAt = -1, t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                int got = c.poll(Duration.ofMillis(300)).count();
                if (got > 0 && firstAt < 0) firstAt = System.currentTimeMillis() - t0;
                n += got;
                boolean done = true;
                for (TopicPartition tp : tps) if (c.position(tp) < end.get(tp)) { done = false; break; }
                if (done) break;
            }
            if (firstAt >= 0) System.out.printf("   （第一批数据 %d ms 后到）%n", firstAt);
            c.commitSync();
            return n;
        }
    }

    // ---------------------------------------------------------------- compact
    static void compact() throws Exception {
        try (Admin admin = admin(); KafkaProducer<String, String> p = producer()) {
            recreate(admin, new NewTopic(STATE, 1, (short) 1).configs(Map.of(
                    "cleanup.policy", "compact", "segment.ms", "2000", "min.cleanable.dirty.ratio", "0.01",
                    "delete.retention.ms", "5000")));
            System.out.println("order-state：1 个分区，cleanup.policy=compact，segment.ms=2000，min.cleanable.dirty.ratio=0.01，delete.retention.ms=5000");
            List<String> orders = new ArrayList<>();
            for (int i = 9001; i <= 10000; i++) orders.add("" + i);           // 1000 个订单，含 9527
            long n = 0;
            for (String st : List.of("CREATED", "PAID")) {
                for (String o : orders) { p.send(new ProducerRecord<>(STATE, o, st)); n++; }
                p.flush(); Thread.sleep(2500);                               // 让段按时间滚动
            }
            for (int i = 0; i < 200; i++) { p.send(new ProducerRecord<>(STATE, orders.get(500 + i), "REFUNDED")); n++; } // 9501..9700 退款，含 9527
            for (int i = 0; i < 100; i++) { p.send(new ProducerRecord<>(STATE, orders.get(i), null)); n++; }             // 9001..9100 归档：墓碑
            p.flush(); Thread.sleep(2500);
            p.send(new ProducerRecord<>(STATE, "10001", "CREATED")).get(); n++; // 再写一条，让上一段滚动成非活跃段
            System.out.printf("一共写了 %d 条：1000 个订单 × CREATED、PAID，200 个 REFUNDED，100 个墓碑（value=null），最后 1 条新订单%n", n);

            long prev = -1; int stable = 0; long t0 = System.currentTimeMillis();
            while (stable < 3 && System.currentTimeMillis() - t0 < 60_000) {
                Thread.sleep(2000);
                List<ConsumerRecord<String, String>> all = readAll(STATE);
                long tomb = all.stream().filter(r -> r.value() == null).count();
                System.out.printf("  %4.1f s：从头读到 %d 条（墓碑 %d 条），本地段 %d 个%n", (System.currentTimeMillis() - t0) / 1000.0,
                        all.size(), tomb, localSegs(STATE));
                stable = all.size() == prev ? stable + 1 : 0; prev = all.size();
            }
            List<ConsumerRecord<String, String>> all = readAll(STATE);
            System.out.println("9527 现在只剩：" + all.stream().filter(r -> "9527".equals(r.key()))
                    .map(r -> "offset " + r.offset() + " " + r.value()).toList());
            System.out.println("最前面 5 条的 offset：" + all.stream().limit(5).map(r -> r.offset() + ":" + r.key() + "=" + r.value()).toList());
            Map<String, Long> by = all.stream().collect(Collectors.groupingBy(r -> String.valueOf(r.value()), TreeMap::new, Collectors.counting()));
            System.out.println("按状态数：" + by);

            // 墓碑要等 delete.retention.ms 过了、再被清理一次才消失：再写一轮 PAID 触发清理
            Thread.sleep(6000);
            for (int i = 300; i < 400; i++) p.send(new ProducerRecord<>(STATE, orders.get(i), "PAID"));
            p.flush(); Thread.sleep(2500);
            p.send(new ProducerRecord<>(STATE, "10002", "CREATED")).get();
            for (int i = 0; i < 6; i++) {
                Thread.sleep(2000);
                List<ConsumerRecord<String, String>> again = readAll(STATE);
                long tomb = again.stream().filter(r -> r.value() == null).count();
                System.out.printf("  墓碑过了 delete.retention.ms 以后又清理一轮：%d 条，其中墓碑 %d 条%n", again.size(), tomb);
                if (tomb == 0) break;
            }
            // 空 key 写进 compact topic
            try {
                p.send(new ProducerRecord<>(STATE, null, "PAID")).get();
                System.out.println("空 key：居然写进去了");
            } catch (ExecutionException e) {
                System.out.println("空 key：" + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            // compact + 分层存储
            try {
                admin.createTopics(List.of(new NewTopic("order-state-tiered", 1, (short) 1).configs(Map.of(
                        "cleanup.policy", "compact", "remote.storage.enable", "true")))).all().get();
                System.out.println("compact + remote.storage.enable：居然建成了");
            } catch (ExecutionException e) {
                System.out.println("compact + remote.storage.enable：" + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- down
    static void down() throws Exception {
        Path store = remoteDir.toPath().resolve("kafka-tiered-storage");
        if (remoteSegs(TIERED) == 0) { System.out.println("先跑 ./run.sh outage，让 order-events-tiered 有远端段"); return; }
        var rwx = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
        var none = java.nio.file.attribute.PosixFilePermissions.fromString("---------");
        try (Admin admin = admin()) {
            Map<TopicPartition, Long> local = earliest(admin, TIERED, OffsetSpec.earliestLocal());
            Map<TopicPartition, Long> zero = local.keySet().stream().collect(Collectors.toMap(tp -> tp, tp -> 0L));
            System.out.printf("远端段 %d 个，本地段 %d 个，本地最早 offset 合计 %d。把远端目录权限改成 000，模拟对象存储连不上%n",
                    remoteSegs(TIERED), localSegs(TIERED), sum(local));
            int localBefore = localSegs(TIERED);
            Files.setPosixFilePermissions(store, none);
            AtomicBoolean run = new AtomicBoolean(true);
            AtomicLong sent = new AtomicLong();
            Thread prod = new Thread(() -> produceLoop(run, sent), "order-svc");
            try {
                long t0 = System.currentTimeMillis();
                prod.start();
                System.out.println("新数据消费者（从本地最早 offset 读）：" + tryRead(local, 10_000));
                System.out.println("老数据消费者（从 offset 0 读，要去远端）：" + tryRead(zero, 15_000));
                Thread.sleep(Math.max(0, t0 + 3 * DAY - System.currentTimeMillis()));
                run.set(false); prod.join();
                System.out.printf("远端断了 3 天：订单服务照常写了 %d 条；order-events-tiered 本地段从 %d 个涨到 %d 个（正常时本地只留 1 天，约 12 个；复制不上去，本地就不删）%n",
                        sent.get(), localBefore, localSegs(TIERED));
            } finally {
                Files.setPosixFilePermissions(store, rwx);
            }
            System.out.println("远端恢复后，老数据消费者再读：" + tryRead(zero, 15_000));
            Thread.sleep(3 * 2000 + 3000);
            System.out.printf("再过几秒：远端段 %d 个，本地段 %d 个%n", remoteSegs(TIERED), localSegs(TIERED));
        }
    }

    static String tryRead(Map<TopicPartition, Long> from, long ms) {
        Properties pr = consumerProps("probe-" + System.nanoTime(), "none");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(pr)) {
            c.assign(from.keySet());
            from.forEach(c::seek);
            long n = 0, t0 = System.currentTimeMillis(), first = -1;
            String err = "";
            while (System.currentTimeMillis() - t0 < ms) {
                try {
                    int k = c.poll(Duration.ofMillis(500)).count();
                    if (k > 0 && first < 0) first = System.currentTimeMillis() - t0;
                    n += k;
                    if (n > 0 && k == 0) break;
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                    break;
                }
            }
            return String.format("%d 秒内读到 %d 条%s%s", (System.currentTimeMillis() - t0) / 1000, n,
                    first >= 0 ? "，第一批 " + first + " ms 后到" : "", err.isEmpty() ? "，没有抛异常" : "，异常 " + err);
        }
    }

    // ---------------------------------------------------------------- helpers
    static List<ConsumerRecord<String, String>> readAll(String topic) {
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProps("reader-" + System.nanoTime(), "earliest"))) {
            TopicPartition tp = new TopicPartition(topic, 0);
            c.assign(List.of(tp)); c.seekToBeginning(List.of(tp));
            long end = c.endOffsets(List.of(tp)).get(tp);
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            while (c.position(tp) < end) for (var r : c.poll(Duration.ofMillis(300))) out.add(r);
            return out;
        }
    }

    static Map<TopicPartition, Long> positions(KafkaConsumer<String, String> c) {
        Map<TopicPartition, Long> m = new HashMap<>();
        for (TopicPartition tp : c.assignment()) m.put(tp, c.position(tp));
        return m;
    }

    static Map<TopicPartition, Long> earliest(Admin admin, String topic, OffsetSpec spec) throws Exception {
        Map<TopicPartition, OffsetSpec> q = new HashMap<>();
        for (int i = 0; i < PARTS; i++) q.put(new TopicPartition(topic, i), spec);
        Map<TopicPartition, Long> m = new HashMap<>();
        admin.listOffsets(q).all().get().forEach((tp, r) -> m.put(tp, r.offset()));
        return m;
    }

    static boolean groupHasOffsets(Admin admin, String group) throws Exception {
        return !admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get().isEmpty();
    }

    static long sum(Map<TopicPartition, Long> m) { return m.values().stream().mapToLong(Long::longValue).sum(); }
    static String kb(long b) { return b / 1024 + " KB"; }

    static List<File> localLogFiles(String topic) {
        File[] dirs = logDir.listFiles(f -> f.isDirectory() && f.getName().matches(java.util.regex.Pattern.quote(topic) + "-\\d+"));
        List<File> out = new ArrayList<>();
        if (dirs != null) for (File d : dirs) {
            File[] fs = d.listFiles(f -> f.getName().endsWith(".log"));
            if (fs != null) out.addAll(Arrays.asList(fs));
        }
        return out;
    }
    static int localSegs(String topic) { return localLogFiles(topic).size(); }
    static long localBytes(String topic) { return localLogFiles(topic).stream().mapToLong(File::length).sum(); }

    /** LocalTieredStorage 把每个分区放在 <dir>/kafka-tiered-storage/<topic>-<分区>-<topicId>/ 下，一个段一个 .log。 */
    static int remoteSegs(String topic) throws Exception {
        Path root = remoteDir.toPath().resolve("kafka-tiered-storage");
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) {
            return (int) s.filter(f -> f.toString().endsWith(".log"))
                    .filter(f -> f.getParent().getFileName().toString().matches(java.util.regex.Pattern.quote(topic) + "-\\d+-.*"))
                    .count();
        }
    }

    static void recreate(Admin admin, NewTopic t) throws Exception {
        if (admin.listTopics().names().get().contains(t.name())) {
            admin.deleteTopics(List.of(t.name())).all().get();
            while (admin.listTopics().names().get().contains(t.name())) Thread.sleep(500);
            Thread.sleep(2000);
        }
        for (int i = 0; ; i++) {
            try { admin.createTopics(List.of(t)).all().get(); return; }
            catch (ExecutionException e) { if (i > 10) throw e; Thread.sleep(1000); }
        }
    }

    static Admin admin() { return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bs)); }

    static KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bs);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "15000");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        return new KafkaProducer<>(p);
    }

    static Properties consumerProps(String group, String reset) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bs);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, reset);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return p;
    }

    static KafkaConsumer<String, String> consumer(String group, String reset) { return new KafkaConsumer<>(consumerProps(group, reset)); }
}
