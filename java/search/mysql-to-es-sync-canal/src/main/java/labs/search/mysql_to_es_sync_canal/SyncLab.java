package labs.search.mysql_to_es_sync_canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 下单 1 秒后商家才搜得到：MySQL 8.4 → Canal 1.1.8（装成从库读 binlog）→ 本程序 → Elasticsearch 9.5。
 *
 * <pre>
 *   e2e     写 100 单（每 40 ms 一单），每一单记下：提交 → Canal 交给本程序 → bulk 写完 → ES 搜得到，各花了多久
 *   order   300 单各连改两次状态（PAID v2 → SHIPPED v3）；同步程序把一批变更打乱顺序再写（模拟多分区、多线程消费）：
 *           不带版本 / 带外部版本号（version_type=external）各跑一次，数 ES 和 MySQL 不一致的单
 *   outage  一边持续改状态，一边把同步程序停 10 秒再启动：积压多少、多久追平
 *   probe   写一单，等 10 秒看 ES 有没有（run.sh 的 purge 演练里用：binlog 被清理以后）
 * </pre>
 * 生产上通常是 Canal → Kafka（按订单号分区）→ 消费者；这里用 Canal 的 TCP 客户端直连，机制一样，少一个组件。
 */
public class SyncLab {

    static final String ES = "http://127.0.0.1:19201";
    static final String IDX = "orders_sync";
    static final String DB = "jdbc:mysql://127.0.0.1:33114/order_db?useSSL=false&allowPublicKeyRetrieval=true";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        if (mode.equals("all") || mode.equals("e2e")) e2e();
        if (mode.equals("all") || mode.equals("order")) order();
        if (mode.equals("all") || mode.equals("outage")) outage();
        if (mode.equals("probe")) probe();
        System.exit(0);
    }

    // ================================================================ 同步程序
    /** 从 Canal 拉一批 → 转成 ES bulk → 写完再 ack。shuffle 模拟多分区乱序，versioned 用订单的 version 做外部版本号。 */
    static final class Syncer implements Runnable {
        final boolean shuffle, versioned;
        volatile boolean running = true, paused = false;
        final Map<Long, Long> recvAt = new ConcurrentHashMap<>(), bulkAt = new ConcurrentHashMap<>();
        final AtomicLong rows = new AtomicLong(), conflicts = new AtomicLong(), batches = new AtomicLong();
        volatile int lastBatchRows = 0;
        Thread thread;

        Syncer(boolean shuffle, boolean versioned) { this.shuffle = shuffle; this.versioned = versioned; }

        Syncer start() { thread = new Thread(this, "syncer"); thread.start(); return this; }

        void stop() throws InterruptedException { running = false; thread.join(); }

        @Override
        public void run() {
            CanalConnector c = CanalConnectors.newSingleConnector(new InetSocketAddress("127.0.0.1", 11111), "orders", "", "");
            try {
                c.connect();
                c.subscribe("order_db\\.t_order");
                c.rollback();                                   // 从上次 ack 的位置接着拿
                while (running) {
                    if (paused) { Thread.sleep(50); continue; }
                    Message msg = c.getWithoutAck(1000, 100L, TimeUnit.MILLISECONDS);
                    long batchId = msg.getId();
                    if (batchId == -1 || msg.getEntries().isEmpty()) { if (batchId != -1) c.ack(batchId); continue; }
                    long now = System.currentTimeMillis();
                    List<Map<String, String>> changes = new ArrayList<>();
                    for (CanalEntry.Entry e : msg.getEntries()) {
                        if (e.getEntryType() != CanalEntry.EntryType.ROWDATA) continue;
                        CanalEntry.RowChange rc = CanalEntry.RowChange.parseFrom(e.getStoreValue());
                        for (CanalEntry.RowData rd : rc.getRowDatasList()) {
                            Map<String, String> row = new LinkedHashMap<>();
                            List<CanalEntry.Column> cols = rc.getEventType() == CanalEntry.EventType.DELETE ? rd.getBeforeColumnsList() : rd.getAfterColumnsList();
                            for (CanalEntry.Column col : cols) row.put(col.getName(), col.getValue());
                            row.put("_op", rc.getEventType().name());
                            changes.add(row);
                            recvAt.putIfAbsent(Long.parseLong(row.get("id")), now);
                        }
                    }
                    if (shuffle) Collections.shuffle(changes, ThreadLocalRandom.current());
                    if (!changes.isEmpty()) bulk(changes);
                    long done = System.currentTimeMillis();
                    for (Map<String, String> r : changes) bulkAt.putIfAbsent(Long.parseLong(r.get("id")), done);
                    rows.addAndGet(changes.size());
                    lastBatchRows = changes.size();
                    batches.incrementAndGet();
                    c.ack(batchId);                              // ES 写成功以后才 ack：没写成就重来，不会丢
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                c.disconnect();
            }
        }

        void bulk(List<Map<String, String>> changes) throws Exception {
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> r : changes) {
                String id = r.get("id");
                String meta = versioned ? ",\"version\":" + r.get("version") + ",\"version_type\":\"external\"" : "";
                if (r.get("_op").equals("DELETE")) {
                    sb.append("{\"delete\":{\"_index\":\"").append(IDX).append("\",\"_id\":\"").append(id).append('"').append(meta).append("}}\n");
                    continue;
                }
                sb.append("{\"index\":{\"_index\":\"").append(IDX).append("\",\"_id\":\"").append(id).append('"').append(meta).append("}}\n");
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("id", Long.parseLong(id));
                doc.put("user_id", Long.parseLong(r.get("user_id")));
                doc.put("shop_id", r.get("shop_id"));
                doc.put("title", r.get("title"));
                doc.put("status", r.get("status"));
                doc.put("version", Integer.parseInt(r.get("version")));
                doc.put("amount_cents", Long.parseLong(r.get("amount_cents")));
                doc.put("updated_at", r.get("updated_at"));
                sb.append(M.writeValueAsString(doc)).append('\n');
            }
            JsonNode res = es("POST", "/_bulk", sb.toString());
            for (JsonNode item : res.path("items")) {
                JsonNode x = item.elements().next();
                if (x.path("status").asInt() == 409) conflicts.incrementAndGet();     // 旧版本被拒：正是我们要的
                else if (x.path("status").asInt() == 404) continue;                   // 删除一个本来就不在 ES 里的文档
                else if (x.path("status").asInt() >= 300) throw new IllegalStateException("bulk 失败：" + x);
            }
        }
    }

    // ================================================================ 工具
    static JsonNode es(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ES + path))
                .header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        String s = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
        return s.isEmpty() ? M.createObjectNode() : M.readTree(s);
    }

    static void resetIndex() throws Exception {
        es("DELETE", "/" + IDX, null);
        es("PUT", "/" + IDX, """
                { "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": { "properties": {
                    "id": { "type": "long" }, "user_id": { "type": "long" }, "shop_id": { "type": "keyword" },
                    "title": { "type": "text", "analyzer": "cjk" }, "status": { "type": "keyword" }, "version": { "type": "integer" },
                    "amount_cents": { "type": "long" }, "updated_at": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss.SSS" } } } }""");
    }

    static Connection db() throws SQLException { return DriverManager.getConnection(DB, "root", "root"); }

    static void resetTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) { st.execute("DELETE FROM t_order"); }
    }

    static long insert(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_order VALUES (?, ?, ?, '88 号机械键盘 青轴', 'WAIT_PAY', 1, 79800, NOW(3))")) {
            ps.setLong(1, id);
            ps.setLong(2, 1000 + id % 50000);
            ps.setLong(3, 301 + id % 50);
            ps.executeUpdate();
        }
        return System.currentTimeMillis();
    }

    static void setStatus(Connection c, long id, String st, int ver) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE t_order SET status = ?, version = ?, updated_at = NOW(3) WHERE id = ?")) {
            ps.setString(1, st);
            ps.setInt(2, ver);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    static Set<Long> visibleIds(Collection<Long> ids) throws Exception {
        String terms = ids.toString();
        JsonNode r = es("POST", "/" + IDX + "/_search", "{\"size\":" + ids.size() + ",\"_source\":[\"id\"],\"query\":{\"terms\":{\"id\":" + terms + "}}}");
        Set<Long> s = new HashSet<>();
        for (JsonNode h : r.path("hits").path("hits")) s.add(h.path("_source").path("id").asLong());
        return s;
    }

    /** ES 里和 MySQL 不一致的单数（按状态和版本比）。 */
    static int mismatches(Connection c) throws Exception {
        es("POST", "/" + IDX + "/_refresh", null);
        Map<Long, String> db = new HashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, status, version FROM t_order")) {
            while (rs.next()) db.put(rs.getLong(1), rs.getString(2) + "/" + rs.getInt(3));
        }
        Map<Long, String> es = new HashMap<>();
        JsonNode r = es("POST", "/" + IDX + "/_search", "{\"size\":10000,\"_source\":[\"id\",\"status\",\"version\"],\"query\":{\"match_all\":{}}}");
        for (JsonNode h : r.path("hits").path("hits"))
            es.put(h.path("_source").path("id").asLong(), h.path("_source").path("status").asText() + "/" + h.path("_source").path("version").asInt());
        int bad = 0;
        for (Map.Entry<Long, String> e : db.entrySet()) if (!e.getValue().equals(es.get(e.getKey()))) bad++;
        return bad;
    }

    static String dist(List<Long> v) {
        Collections.sort(v);
        return String.format("中位 %d ms，p90 %d ms，最大 %d ms", v.get(v.size() / 2), v.get((int) (v.size() * 0.9)), v.get(v.size() - 1));
    }

    static void title(String t) { System.out.println(); System.out.println("==== " + t); }

    // ================================================================ e2e
    static void e2e() throws Exception {
        resetIndex();
        try (Connection c = db()) {
            resetTable(c);
            Syncer s = new Syncer(false, true).start();
            Thread.sleep(1500);
            Map<Long, Long> commitAt = new ConcurrentHashMap<>();
            Map<Long, Long> visAt = new ConcurrentHashMap<>();
            // 另一个线程边写边查：每 20 ms 搜一次所有已提交、还没搜到的单
            AtomicBoolean writing = new AtomicBoolean(true);
            Thread poller = new Thread(() -> {
                long deadline = System.currentTimeMillis() + 60_000;
                try {
                    while ((writing.get() || visAt.size() < commitAt.size()) && System.currentTimeMillis() < deadline) {
                        Set<Long> pending = new HashSet<>(commitAt.keySet());
                        pending.removeAll(visAt.keySet());
                        if (!pending.isEmpty()) {
                            Set<Long> v = visibleIds(pending);
                            long now = System.currentTimeMillis();
                            for (Long id : v) visAt.putIfAbsent(id, now);
                        }
                        Thread.sleep(20);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            });
            poller.start();
            for (long i = 1; i <= 100; i++) { long id = 10_000L + i; long t = insert(c, id); commitAt.put(id, t); Thread.sleep(40); }
            writing.set(false);
            poller.join();
            s.stop();
            List<Long> a = new ArrayList<>(), b = new ArrayList<>(), d = new ArrayList<>(), tot = new ArrayList<>();
            for (long id : commitAt.keySet()) {
                if (!visAt.containsKey(id) || !s.recvAt.containsKey(id)) continue;
                a.add(s.recvAt.get(id) - commitAt.get(id));
                b.add(s.bulkAt.get(id) - s.recvAt.get(id));
                d.add(Math.max(0, visAt.get(id) - s.bulkAt.get(id)));
                tot.add(visAt.get(id) - commitAt.get(id));
            }
            title("e2e：写 100 单（每 40 ms 一单），从 MySQL 提交到商家在 ES 里搜得到");
            System.out.println("   提交 → Canal 交给同步程序：" + dist(a));
            System.out.println("   同步程序 bulk 写 ES：      " + dist(b));
            System.out.println("   ES 写完 → 搜得到（refresh）：" + dist(d));
            System.out.println("   合计：                     " + dist(tot) + "（" + tot.size() + " 单都搜到了）");
        }
    }

    // ================================================================ order
    static void order() throws Exception {
        for (boolean versioned : new boolean[]{false, true}) {
            resetIndex();
            try (Connection c = db()) {
                resetTable(c);
                Syncer s = new Syncer(true, versioned).start();
                Thread.sleep(1500);
                long base = versioned ? 25_000 : 20_000;          // 两轮用不同的订单号（见页面坑：删除留下的版本墓碑）
                for (long id = base + 1; id <= base + 300; id++) insert(c, id);
                Thread.sleep(3000);
                for (long id = base + 1; id <= base + 300; id++) {      // 每单连改两次：付款、发货
                    setStatus(c, id, "PAID", 2);
                    setStatus(c, id, "SHIPPED", 3);
                }
                Thread.sleep(4000);
                s.stop();
                title("order（同步程序打乱一批里的顺序，" + (versioned ? "带外部版本号 version_type=external" : "不带版本，后写的覆盖先写的") + "）");
                System.out.println("   300 单各改两次，同步了 " + s.rows.get() + " 条变更，分 " + s.batches.get() + " 批；被 ES 以版本冲突（409）拒掉 " + s.conflicts.get() + " 条");
                System.out.println("   最后 ES 和 MySQL 不一致的单：" + mismatches(c));
            }
        }
    }

    // ================================================================ outage
    static void outage() throws Exception {
        resetIndex();
        try (Connection c = db(); Connection w = db()) {
            resetTable(c);
            Syncer s = new Syncer(false, true).start();
            Thread.sleep(1500);
            for (long id = 30_001; id <= 30_200; id++) insert(c, id);
            Thread.sleep(3000);
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicLong updates = new AtomicLong();
            Thread writer = new Thread(() -> {
                int ver = 2;
                try {
                    while (writing.get()) {
                        for (long id = 30_001; id <= 30_200 && writing.get(); id += 10) {
                            setStatus(w, id, ver % 2 == 0 ? "PAID" : "SHIPPED", ver);
                            updates.incrementAndGet();
                            Thread.sleep(50);                       // 每秒约 20 次更新
                        }
                        ver++;
                    }
                } catch (Exception e) { e.printStackTrace(); }
            });
            writer.start();
            Thread.sleep(2000);
            long rowsBefore = s.rows.get();
            s.paused = true;
            long t0 = System.currentTimeMillis();
            Thread.sleep(10_000);
            long missed = updates.get();
            s.paused = false;
            long resumeAt = System.currentTimeMillis();
            Thread.sleep(300);
            int firstBatch = s.lastBatchRows;
            writing.set(false);
            writer.join();
            long caught = -1;
            for (int i = 0; i < 200; i++) {
                if (mismatches(c) == 0) { caught = System.currentTimeMillis() - resumeAt; break; }
                Thread.sleep(100);
            }
            s.stop();
            title("outage：同步程序停 10 秒（Canal server 还在），期间每秒约 20 次更新");
            System.out.println("   停之前已同步 " + rowsBefore + " 条；停了 " + (resumeAt - t0) + " ms；恢复后第一批拿到 " + firstBatch + " 条积压变更");
            System.out.println("   恢复后 " + caught + " ms 内 ES 和 MySQL 完全一致（总共改了 " + updates.get() + " 次）");
        }
    }

    // ================================================================ probe
    static void probe() throws Exception {
        try (Connection c = db()) {
            Syncer s = new Syncer(false, true).start();
            long id = 40_001;
            try (Statement st = c.createStatement()) { st.execute("DELETE FROM t_order WHERE id = " + id); }
            insert(c, id);
            Thread.sleep(10_000);
            s.stop();
            es("POST", "/" + IDX + "/_refresh", null);
            System.out.println("   probe：写入订单 " + id + "，10 秒后 ES 里" + (visibleIds(List.of(id)).isEmpty() ? "没有" : "有") + "；同步程序这 10 秒收到 " + s.rows.get() + " 条变更");
        }
    }
}
