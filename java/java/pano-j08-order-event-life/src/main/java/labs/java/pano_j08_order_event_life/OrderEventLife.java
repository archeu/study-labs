package labs.java.pano_j08_order_event_life;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 全景 J08：一条订单消息的一生。
 * 订单 9527 付款成功，订单服务发一条 ORDER_PAID 事件到 order-events（6 分区、3 副本、min.insync.replicas=2），
 * 库存、积分、通知三个消费组各自消费、处理、提交位点。
 *
 * 子命令：
 *   trace  三个组先加入，再发 9527 那条，逐站计时；然后连发 50 条，比较 linger.ms=5（默认）和 0
 *   send   发一条，等 ACK 最多 6 秒（副本挂掉演练时由 run.sh 调用）
 *   dup    积分服务处理完、还没提交位点就“崩了”，同组新实例再收到一次，靠 eventId 去重
 */
public class OrderEventLife {
    static final String BS = System.getenv().getOrDefault("BOOTSTRAP", "localhost:19092,localhost:29092,localhost:39092");
    static final String TOPIC = "order-events";
    static final String[] GROUPS = {"inventory-svc", "points-svc", "notify-svc"};

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "trace";
        switch (cmd) {
            case "trace" -> trace();
            case "send" -> sendOnce(args.length > 1 ? args[1] : "9527");
            case "dup" -> dup();
            default -> System.out.println("trace | send [orderId] | dup");
        }
    }

    static String event(long orderId, String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"ORDER_PAID\",\"orderId\":" + orderId
                + ",\"userId\":1024,\"productId\":88,\"qty\":2,\"amount\":\"798.00\",\"paidAt\":\"2026-11-14T20:00:05+08:00\"}";
    }

    static KafkaProducer<String, String> producer(int lingerMs, int deliveryTimeoutMs) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BS);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // acks=all、enable.idempotence=true 是 Kafka 3.0 起的默认值，这里不写；只改要对比的 linger.ms
        p.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        if (deliveryTimeoutMs > 0) {
            p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, deliveryTimeoutMs - 1000);
            p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        }
        return new KafkaProducer<>(p);
    }

    static KafkaConsumer<String, String> consumer(String group, String reset) {
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BS);
        c.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);   // 处理完再手动提交
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, reset);
        return new KafkaConsumer<>(c);
    }

    // ---------------- trace ----------------
    record Got(String group, long recvNs, int partition, long offset, long commitUs) {}

    static void trace() throws Exception {
        System.out.println("== trace：订单 9527 付款成功，发一条 ORDER_PAID");
        int p9527 = Utils.toPositive(Utils.murmur2("9527".getBytes(StandardCharsets.UTF_8))) % 6;
        System.out.printf("   2  分区器：key \"9527\" → murmur2 → 正数 %% 6 = 分区 %d%n", p9527);
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BS))) {
            TopicDescription d = admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC);
            var pi = d.partitions().get(p9527);
            System.out.printf("      分区 %d：leader = broker %d，副本 %s，ISR %s%n", p9527, pi.leader().id(),
                    pi.replicas().stream().map(n -> n.id()).toList(), pi.isr().stream().map(n -> n.id()).toList());
        }

        // 三个消费组先加入，等分到分区
        Map<String, Got> got = new ConcurrentHashMap<>();
        Map<String, List<long[]>> bench = new ConcurrentHashMap<>();
        CountDownLatch ready = new CountDownLatch(3);
        Map<String, Long> joinMs = new ConcurrentHashMap<>();
        Map<String, Long> sentAt = new ConcurrentHashMap<>();   // eventId → send() 调用时刻
        Set<String> processed = ConcurrentHashMap.newKeySet();  // 模拟各服务的去重表（group:eventId）
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (String g : GROUPS) {
            pool.submit(() -> {
                try (var c = consumer(g, "latest")) {
                    long t0 = System.nanoTime();
                    c.subscribe(List.of(TOPIC));
                    while (c.assignment().isEmpty()) c.poll(Duration.ofMillis(50));
                    for (TopicPartition tp : c.assignment()) c.position(tp);  // 定下起点
                    joinMs.put(g, (System.nanoTime() - t0) / 1_000_000);
                    ready.countDown();
                    while (running.get()) {
                        var recs = c.poll(Duration.ofMillis(100));
                        for (ConsumerRecord<String, String> r : recs) {
                            long now = System.nanoTime();
                            String eid = new String(r.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
                            processed.add(g + ":" + eid);
                            long c0 = System.nanoTime();
                            c.commitSync(Map.of(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1)));
                            long commitUs = (System.nanoTime() - c0) / 1000;
                            Long s = sentAt.get(eid);
                            if (eid.equals("evt-9527-paid")) got.put(g, new Got(g, now, r.partition(), r.offset(), commitUs));
                            else if (s != null) bench.computeIfAbsent(g + "|" + eid.split("-")[1], k -> new CopyOnWriteArrayList<>())
                                    .add(new long[]{(now - s) / 1000, commitUs});
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
                return null;
            });
        }
        ready.await();
        System.out.printf("   7  三个组加入并分到分区：%s ms%n", joinMs);

        try (var prod = producer(5, 0)) {
            // 先预热一条，拿到元数据和连接（第一条 send 要多等一次 Metadata 请求）
            long w0 = System.nanoTime();
            prod.send(rec(1, "evt-warm")).get();
            System.out.printf("      预热一条（含拉元数据、建连接、拿 producerId）：%.1f ms%n", (System.nanoTime() - w0) / 1e6);
            Thread.sleep(500);

            long t0 = System.nanoTime();
            sentAt.put("evt-9527-paid", t0);
            var f = new CompletableFuture<long[]>();
            prod.send(rec(9527, "evt-9527-paid"), (md, ex) -> {
                if (ex != null) f.completeExceptionally(ex);
                else f.complete(new long[]{System.nanoTime(), md.partition(), md.offset()});
            });
            long sendReturn = System.nanoTime();
            long[] ack = f.get();
            System.out.printf("   3  send() 返回（只进了攒批区）：%d µs%n", (sendReturn - t0) / 1000);
            System.out.printf("   6  ACK 回来（acks=all，linger.ms=5）：%.2f ms，分区 %d，offset %d%n",
                    (ack[0] - t0) / 1e6, ack[1], ack[2]);
            long deadline = System.currentTimeMillis() + 5000;
            while (got.size() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(5);
            for (String g : GROUPS) {
                Got x = got.get(g);
                if (x == null) { System.out.printf("   8  %-14s 没收到%n", g); continue; }
                System.out.printf("   8  %-14s 收到：发出后 %.2f ms，分区 %d offset %d；9 commitSync %.2f ms%n",
                        g, (x.recvNs() - t0) / 1e6, x.partition(), x.offset(), x.commitUs() / 1000.0);
            }
        }

        // 连发 50 条：linger.ms=5（默认）vs 0
        for (int linger : new int[]{5, 0}) {
            try (var prod = producer(linger, 0)) {
                prod.send(rec(1, "evt-warm" + linger)).get();
                long[] acks = new long[50];
                for (int i = 0; i < 50; i++) {
                    String eid = "evt-L" + linger + "-" + i;
                    long t = System.nanoTime();
                    sentAt.put(eid, t);
                    prod.send(rec(10000 + i, eid)).get();   // 一条一条同步发：最坏情况，每条都等满 linger
                    acks[i] = (System.nanoTime() - t) / 1000;
                    Thread.sleep(20);
                }
                Thread.sleep(1000);
                Arrays.sort(acks);
                System.out.printf("== linger.ms=%d：50 条逐条同步发，ACK p50 %.2f ms，p90 %.2f ms%n",
                        linger, acks[25] / 1000.0, acks[45] / 1000.0);
                for (String g : GROUPS) {
                    List<long[]> l = new ArrayList<>();
                    for (var e : bench.entrySet()) if (e.getKey().equals(g + "|L" + linger)) l.addAll(e.getValue());
                    long[] e2e = l.stream().mapToLong(a -> a[0]).sorted().toArray();
                    long[] cm = l.stream().mapToLong(a -> a[1]).sorted().toArray();
                    if (e2e.length == 0) continue;
                    System.out.printf("   %-14s 收到 %d 条，发出→收到 p50 %.2f ms；commitSync p50 %.2f ms%n",
                            g, e2e.length, e2e[e2e.length / 2] / 1000.0, cm[cm.length / 2] / 1000.0);
                }
            }
        }
        running.set(false);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    static ProducerRecord<String, String> rec(long orderId, String eventId) {
        var r = new ProducerRecord<>(TOPIC, String.valueOf(orderId), event(orderId, eventId));
        r.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    // ---------------- send（副本挂掉演练） ----------------
    static void sendOnce(String orderId) {
        try (var prod = producer(5, 6000)) {
            long t0 = System.nanoTime();
            try {
                RecordMetadata md = prod.send(rec(Long.parseLong(orderId), "evt-" + orderId + "-isr-" + System.currentTimeMillis())).get();
                System.out.printf("      → ACK，分区 %d offset %d，%.1f ms%n", md.partition(), md.offset(), (System.nanoTime() - t0) / 1e6);
            } catch (ExecutionException e) {
                System.out.printf("      → 失败：%s（%.1f s 后放弃）%n        %s%n", e.getCause().getClass().getSimpleName(), (System.nanoTime() - t0) / 1e9, e.getCause().getMessage());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ---------------- dup：处理完、没提交就崩 ----------------
    static void dup() throws Exception {
        System.out.println("== dup：积分服务给 1024 加了 798 分，还没提交位点进程就没了");
        String group = "points-svc-dup";   // 单独一个组，从头读，免得和 trace 的位点混在一起
        Set<String> doneTable = new HashSet<>();   // 模拟 points_log 表上 eventId 的唯一键
        int[] points = {0};
        int delivered = 0;
        for (int inst = 1; inst <= 2; inst++) {
            try (var c = consumer(group, "earliest")) {
                c.subscribe(List.of(TOPIC));
                long deadline = System.currentTimeMillis() + 15000;
                boolean found = false;
                while (!found && System.currentTimeMillis() < deadline) {
                    for (var r : c.poll(Duration.ofMillis(200))) {
                        String eid = new String(r.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
                        if (!eid.equals("evt-9527-paid")) continue;
                        delivered++;
                        boolean fresh = doneTable.add(eid);
                        if (fresh) points[0] += 798;
                        System.out.printf("   实例 %d 收到 evt-9527-paid（分区 %d offset %d）：%s，当前积分 +%d%n", inst,
                                r.partition(), r.offset(), fresh ? "第一次，加 798 分" : "去重表里已有，跳过", points[0]);
                        found = true;
                        break;
                    }
                }
                if (inst == 2) c.commitSync();
                // 实例 1：不提交就 close，等同于处理完、提交前进程被杀
            }
        }
        System.out.printf("   投递 %d 次，积分只加了 %d（没有去重会是 %d）%n", delivered, points[0], delivered * 798);
    }
}
