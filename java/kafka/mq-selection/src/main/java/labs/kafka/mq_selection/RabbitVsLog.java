package labs.kafka.mq_selection;

import com.rabbitmq.client.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同一批订单事件，放进 RabbitMQ 的两种队列，看“读完就删”和“读完留着”差在哪。
 *
 * 场景 A：后来才加的消费者（搜索服务上线晚了）
 *   - quorum 队列：消息被积分服务确认以后就没了，新绑的队列也拿不到以前的消息
 *   - stream 队列：谁来都能从头读，读了也不删
 * 场景 B：一条毒消息（订单 1003 的数据坏了，每次处理都抛异常）
 *   - quorum 队列：1003 反复重投，别的订单不受影响
 *   - 用 basic.nack 放回：4.3 起不算失败投递，永远到不了上限（实验里 60 次后手动 ack 掉）
 *   - 用 basic.reject 放回：算失败投递，到默认上限 20 后进死信队列
 * 场景 C：按路由键分发（RabbitMQ 交换机的看家本领）
 *   - topic 交换机：退款服务只收 order.refund.*，积分服务收 order.*.*
 *
 * 场景 D：30 分钟未付自动取消（这里缩成 3 秒）
 *   - 4.4 起 quorum 队列原生支持延迟投递：消息头 x-opt-delivery-time = 到点的毫秒时间戳
 *
 * 运行前：docker run -d --name mq-sel-rabbit -p 5672:5672 rabbitmq:4.4.0
 * JDK 8 写法，用 JDK 17 编译运行。
 */
public class RabbitVsLog {

    static final String[] ORDERS = {"1001", "1002", "1003", "1004", "1005"};

    public static void main(String[] args) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(System.getProperty("host", "localhost"));
        try (Connection conn = f.newConnection("mq-selection")) {
            sceneA(conn);
            sceneB(conn);
            sceneC(conn);
            sceneD(conn);
        }
    }

    // ---------------- 场景 A ----------------
    static void sceneA(Connection conn) throws Exception {
        System.out.println("== 场景 A：后来才加的消费者 ==");
        Channel ch = conn.createChannel();
        ch.exchangeDeclare("order.events", BuiltinExchangeType.FANOUT, true);
        ch.queueDeclare("q.points", true, false, false, quorum());
        ch.queueBind("q.points", "order.events", "");
        ch.queueDeclare("s.orders", true, false, false, stream());
        ch.queueBind("s.orders", "order.events", "");

        ch.confirmSelect();                   // 发布确认，相当于 Kafka 的 acks
        for (String id : ORDERS) {
            ch.basicPublish("order.events", "", MessageProperties.PERSISTENT_TEXT_PLAIN, bytes("paid:" + id));
        }
        ch.waitForConfirmsOrDie(5000);
        Thread.sleep(300);
        System.out.println("发了 5 条，q.points 里有 " + depth(ch, "q.points") + " 条");

        // 积分服务把 q.points 消费完并逐条 ack
        List<String> got = drain(conn, "q.points", null, 5);
        System.out.println("积分服务读到 " + got + "，ack 后 q.points 还剩 " + depth(ch, "q.points") + " 条");

        // 搜索服务上线晚了：新建队列并绑定，以前的消息一条也拿不到
        ch.queueDeclare("q.search", true, false, false, quorum());
        ch.queueBind("q.search", "order.events", "");
        Thread.sleep(300);
        System.out.println("搜索服务晚绑的 q.search 里有 " + depth(ch, "q.search") + " 条");

        // stream：两个“组”各自从头读，互不影响，读完不删
        List<String> r1 = drain(conn, "s.orders", "first", 5);
        List<String> r2 = drain(conn, "s.orders", "first", 5);
        System.out.println("stream 第一次从头读 " + r1.size() + " 条，第二次从头读 " + r2.size() + " 条");
        ch.close();
    }

    // ---------------- 场景 B ----------------
    static void sceneB(Connection conn) throws Exception {
        System.out.println("== 场景 B：订单 1003 是毒消息 ==");
        poison(conn, "nack");     // basic.nack(requeue=true)
        poison(conn, "reject");   // basic.reject(requeue=true)
    }

    /** how = nack 或 reject；1003 每次都处理失败并放回队列，最多看 60 次 */
    static void poison(Connection conn, final String how) throws Exception {
        String q = "q.notify." + how, dlq = q + ".dlq";
        Channel ch = conn.createChannel();
        ch.queueDeclare(dlq, true, false, false, quorum());
        Map<String, Object> a = quorum();
        a.put("x-dead-letter-exchange", "");
        a.put("x-dead-letter-routing-key", dlq);
        // 不设 x-delivery-limit，用 4.x 的默认值
        ch.queueDeclare(q, true, false, false, a);
        for (String id : ORDERS) {
            ch.basicPublish("", q, MessageProperties.PERSISTENT_TEXT_PLAIN, bytes("paid:" + id));
        }

        final Channel c = conn.createChannel();
        c.basicQos(1);
        final AtomicInteger tries = new AtomicInteger();
        final List<String> done = Collections.synchronizedList(new ArrayList<String>());
        final String[] last = {""};
        final String tag = c.basicConsume(q, false, new DefaultConsumer(c) {
            @Override
            public void handleDelivery(String t, Envelope env, AMQP.BasicProperties p, byte[] body) throws java.io.IOException {
                String msg = new String(body, StandardCharsets.UTF_8);
                if (!msg.endsWith("1003")) {
                    done.add(msg.substring(5));
                    getChannel().basicAck(env.getDeliveryTag(), false);
                    return;
                }
                int n = tries.incrementAndGet();
                Map<String, Object> h = p.getHeaders();
                last[0] = "x-delivery-count=" + (h == null ? null : h.get("x-delivery-count"))
                        + ", x-acquired-count=" + (h == null ? null : h.get("x-acquired-count"));
                if (n >= 60) {                     // 实验保护：60 次还没进死信，就手动 ack 掉
                    getChannel().basicAck(env.getDeliveryTag(), false);
                } else if ("nack".equals(how)) {
                    getChannel().basicNack(env.getDeliveryTag(), false, true);
                } else {
                    getChannel().basicReject(env.getDeliveryTag(), true);
                }
            }
        });
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 10000 && depth(ch, dlq) == 0 && tries.get() < 60) Thread.sleep(50);
        Thread.sleep(500);
        c.basicCancel(tag);
        System.out.println("[" + how + "] 其余订单照常处理完：" + done
                + "；1003 被投递 " + tries.get() + " 次，最后一次 " + last[0]
                + "；死信队列 " + depth(ch, dlq) + " 条，原队列剩 " + depth(ch, q) + " 条");
        c.close();
        ch.close();
    }

    // ---------------- 场景 C ----------------
    static void sceneC(Connection conn) throws Exception {
        System.out.println("== 场景 C：按路由键分发 ==");
        Channel ch = conn.createChannel();
        ch.exchangeDeclare("order.topic", BuiltinExchangeType.TOPIC, true);
        ch.queueDeclare("q.refund", true, false, false, quorum());
        ch.queueBind("q.refund", "order.topic", "order.refund.*");
        ch.queueDeclare("q.vip", true, false, false, quorum());
        ch.queueBind("q.vip", "order.topic", "order.*.vip");
        String[][] msgs = {{"order.paid.normal", "1001"}, {"order.paid.vip", "1002"},
                {"order.refund.normal", "1003"}, {"order.refund.vip", "1004"}};
        for (String[] m : msgs) ch.basicPublish("order.topic", m[0], null, bytes(m[0] + ":" + m[1]));
        Thread.sleep(300);
        System.out.println("退款服务 q.refund 收到：" + drain(conn, "q.refund", null, 4));
        System.out.println("VIP 服务 q.vip 收到：" + drain(conn, "q.vip", null, 4));
        ch.close();
    }

    // ---------------- 场景 D ----------------
    static void sceneD(Connection conn) throws Exception {
        System.out.println("== 场景 D：下单 3 秒未付就取消（quorum 队列原生延迟，4.4 起） ==");
        Channel ch = conn.createChannel();
        ch.queueDeclare("q.cancel", true, false, false, quorum());
        final long sent = System.currentTimeMillis();
        long[] delays = {3000, 1000, 0};
        String[] ids = {"2001", "2002", "2003"};
        for (int i = 0; i < ids.length; i++) {
            Map<String, Object> h = new HashMap<String, Object>();
            h.put("x-opt-delivery-time", sent + delays[i]);
            AMQP.BasicProperties p = new AMQP.BasicProperties.Builder().deliveryMode(2).headers(h).build();
            ch.basicPublish("", "q.cancel", p, bytes("cancel:" + ids[i] + "(+" + delays[i] + "ms)"));
        }
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch all = new CountDownLatch(ids.length);
        Channel c = conn.createChannel();
        c.basicQos(10);
        c.basicConsume("q.cancel", false, new DefaultConsumer(c) {
            @Override
            public void handleDelivery(String t, Envelope env, AMQP.BasicProperties p, byte[] body) throws java.io.IOException {
                String s = new String(body, StandardCharsets.UTF_8);
                got.add(s.substring(7) + " 在第 " + (System.currentTimeMillis() - sent) + " ms 到");
                getChannel().basicAck(env.getDeliveryTag(), false);
                all.countDown();
            }
        });
        all.await(10, TimeUnit.SECONDS);
        System.out.println("消费者收到：" + got);
        c.close();
        ch.close();
    }

    // ---------------- 工具 ----------------
    static Map<String, Object> quorum() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("x-queue-type", "quorum");
        return m;
    }

    static Map<String, Object> stream() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("x-queue-type", "stream");
        return m;
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static long depth(Channel ch, String q) throws java.io.IOException {
        return ch.queueDeclarePassive(q).getMessageCount();
    }

    /** 订阅一个队列，收满 max 条或 1.5 秒没有新消息就停；streamOffset 非空时按 stream 方式读 */
    static List<String> drain(Connection conn, String q, String streamOffset, int max) throws Exception {
        final Channel c = conn.createChannel();
        c.basicQos(100);                       // stream 消费者必须设 prefetch
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch full = new CountDownLatch(max);
        Map<String, Object> args = new HashMap<String, Object>();
        if (streamOffset != null) args.put("x-stream-offset", streamOffset);
        c.basicConsume(q, false, args, new DefaultConsumer(c) {
            @Override
            public void handleDelivery(String tag, Envelope env, AMQP.BasicProperties p, byte[] body) throws java.io.IOException {
                String s = new String(body, StandardCharsets.UTF_8);
                got.add(s.substring(s.indexOf(':') + 1));
                getChannel().basicAck(env.getDeliveryTag(), false);
                full.countDown();
            }
        });
        full.await(1500, TimeUnit.MILLISECONDS);
        c.close();
        return new ArrayList<String>(got);
    }
}
