package labs.database.mysql_vs_pg_pgvector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * 值班助手的故障单库：业务数据和向量放在同一个 PostgreSQL 里（pgvector）。
 *
 *   setup   装扩展、建 tickets 表、把 16 张故障单连同向量在一个事务里写进去
 *   see     ⑤ “找相似故障”：一条 SQL 里同时有业务过滤（服务、时间）和向量排序；新单子写入后马上能搜到
 *   filter  10 万行 128 维合成向量 + HNSW：带 WHERE 过滤时结果为什么会少于 LIMIT，iterative scan 怎么补，
 *           加了 B-tree 之后计划器怎么改走精确路线
 *
 * 向量：优先调本机 ollama 的 bge-m3（1024 维）；连不上就退回“字二元组哈希”向量（同样 1024 维，只能看字面相似）。
 * 用 JDK 8 写法，只依赖 pgJDBC；向量以 '[0.1,0.2,…]' 文本传进去，再 ::vector 转换。
 */
public class VecLab {
    static final String URL_ = System.getProperty("pg.url", "jdbc:postgresql://127.0.0.1:55419/postgres?prepareThreshold=0");
    static final String OLLAMA = System.getProperty("ollama", "http://127.0.0.1:11434");
    static final int DIM = 1024;
    static boolean fake = false;

    static final String[][] TICKETS = {
        // service, severity, 多少天前, 标题, 处理记录
        {"payment", "P1", "3",  "付款接口大量超时", "HikariCP 连接池拿不到连接，等待 30 秒后抛 SQLTransientConnectionException；慢 SQL 占着连接不放，扩容连接池后仍然超时，最后给对账 SQL 加索引解决"},
        {"payment", "P2", "20", "付款回调重复入账", "支付渠道重试回调，幂等键没加唯一索引，同一笔付款记了两次账；补唯一约束并按 out_trade_no 去重"},
        {"payment", "P1", "45", "付款后订单仍显示待付款", "读写分离，付款成功后马上从从库读订单，复制延迟 3 秒；关键读改走主库"},
        {"payment", "P2", "200","付款服务升级后连不上数据库", "MySQL 升到 8.4 后 mysql_native_password 默认关闭，老驱动认证失败；升级 Connector/J 并改用 caching_sha2_password"},
        {"order",   "P1", "7",  "下单接口卡住，大量线程 WAITING", "给 orders 表加列，DDL 等元数据锁，后面的查询全部排队；改用 pt-online-schema-change 并设 lock_wait_timeout"},
        {"order",   "P2", "15", "订单列表翻页越来越慢", "深分页 LIMIT 100000,20 扫描大量行；改成按 id 游标翻页"},
        {"order",   "P3", "60", "订单导出 OOM", "一次把 50 万行读进内存；改成流式读取 fetchSize 分批"},
        {"order",   "P2", "90", "库存扣减出现死锁", "两个事务按不同顺序锁 sku 行，互相等待；统一按 sku_id 升序加锁"},
        {"gateway", "P1", "2",  "网关 502 突增", "上游 Tomcat 线程池打满，keepalive 连接被对端关闭后复用报错；调大 keepalive_timeout 并加重试"},
        {"gateway", "P2", "30", "部分请求 TLS 握手失败", "证书链缺中间证书，老 Android 客户端校验失败；补全证书链"},
        {"gateway", "P3", "120","限流误伤正常用户", "按 IP 限流，公司出口 NAT 共用一个 IP；改为按用户 id 限流"},
        {"search",  "P2", "10", "商品搜索结果不全", "Elasticsearch 增量同步消费 binlog 落后，新商品半小时后才能搜到；加消费者并监控延迟"},
        {"search",  "P3", "50", "搜索接口 CPU 飙高", "正则查询导致回溯，单次 2 秒；改成前缀查询"},
        {"payment", "P2", "5",  "退款接口偶发超时", "退款事务里调用渠道接口，事务和连接被长时间占用，连接池耗尽；把远程调用移出事务"},
        {"order",   "P1", "1",  "支付成功消息丢失", "Kafka 生产者 acks=1，broker 切主时丢了消息；改成 acks=all 并开启幂等生产者"},
        {"infra",   "P2", "25", "数据库磁盘一周涨了一倍", "PostgreSQL 表频繁 UPDATE，长事务挡住 VACUUM，死元组清不掉；杀掉长事务并调低 autovacuum 阈值"},
    };

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "see";
        try (Connection c = DriverManager.getConnection(URL_, "postgres", "pg")) {
            if (cmd.equals("setup")) setup(c);
            else if (cmd.equals("see")) see(c);
            else if (cmd.equals("filter")) filter(c);
            else throw new IllegalArgumentException("setup | see | filter");
        }
    }

    // ---------------------------------------------------------------- setup
    static void setup(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            try (ResultSet r = s.executeQuery("SELECT version(), extversion FROM pg_extension WHERE extname='vector'")) {
                r.next();
                System.out.println("[setup] " + r.getString(1).replaceAll(" on .*", "") + " | pgvector " + r.getString(2));
            }
            s.execute("DROP TABLE IF EXISTS tickets");
            s.execute("CREATE TABLE tickets (id bigserial PRIMARY KEY, service text NOT NULL, severity text NOT NULL," +
                    " title text NOT NULL, body text NOT NULL, created_at timestamptz NOT NULL," +
                    " embedding vector(" + DIM + ") NOT NULL)");
        }
        List<String> texts = new ArrayList<String>();
        for (String[] t : TICKETS) texts.add(t[3] + "。" + t[4]);
        long t0 = System.nanoTime();
        List<float[]> vecs = embed(texts);
        System.out.printf("[setup] 向量化 %d 张单子：%s，%d 维，%.1f s%n", texts.size(),
                fake ? "字二元组哈希（ollama 不可用）" : "ollama bge-m3", vecs.get(0).length, (System.nanoTime() - t0) / 1e9);
        c.setAutoCommit(false);   // 业务字段和向量同一个事务：要么都在，要么都不在
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tickets(service,severity,title,body,created_at,embedding)" +
                " VALUES (?,?,?,?, now() - make_interval(days => ?), ?::vector)")) {
            for (int i = 0; i < TICKETS.length; i++) {
                String[] t = TICKETS[i];
                ps.setString(1, t[0]); ps.setString(2, t[1]); ps.setString(3, t[3]); ps.setString(4, t[4]);
                ps.setInt(5, Integer.parseInt(t[2])); ps.setString(6, lit(vecs.get(i)));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        c.commit();
        c.setAutoCommit(true);
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT count(*), pg_size_pretty(pg_total_relation_size('tickets')), pg_column_size(embedding) FROM tickets GROUP BY 3")) {
            r.next();
            System.out.printf("[setup] tickets %d 行，整表 %s；一个 vector(%d) 占 %d 字节（4 字节 × 维数 + 8 字节头）%n",
                    r.getInt(1), r.getString(2), DIM, r.getInt(3));
        }
    }

    // ---------------------------------------------------------------- see（⑤）
    static final String SIMILAR =
            "SELECT id, service, severity, title, round((embedding <=> ?::vector)::numeric, 3) AS dist\n" +
            "FROM tickets\n" +
            "WHERE service = ? AND created_at > now() - interval '90 days'\n" +
            "ORDER BY embedding <=> ?::vector\n" +
            "LIMIT 3";

    static void see(Connection c) throws Exception {
        String q = "付款的时候数据库连接不够用，请求一直在等";
        float[] qv = embed(Collections.singletonList(q)).get(0);
        System.out.println("[see] 新告警：“" + q + "”（" + (fake ? "哈希向量" : "bge-m3") + "）");
        System.out.println("[see] 只按向量排，不过滤：");
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, service, title, round((embedding <=> ?::vector)::numeric,3) FROM tickets ORDER BY embedding <=> ?::vector LIMIT 5")) {
            ps.setString(1, lit(qv)); ps.setString(2, lit(qv));
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) System.out.printf("      #%-2d %-8s %-22s 距离 %s%n", r.getLong(1), r.getString(2), r.getString(3), r.getString(4));
            }
        }
        System.out.println("[see] 同一条 SQL 再加业务条件：service='payment' 且 90 天内");
        printSimilar(c, qv, "payment");

        // 新单子：业务行和向量一个事务写入，提交后下一条查询就能搜到；回滚则两样都没有
        String title = "付款高峰期数据库连接池耗尽", body = "大促期间付款 QPS 翻倍，连接池 20 个连接全被慢事务占住，新请求等待超时；临时扩容并拆分慢事务";
        float[] nv = embed(Collections.singletonList(title + "。" + body)).get(0);
        c.setAutoCommit(false);
        long id;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tickets(service,severity,title,body,created_at,embedding)" +
                " VALUES ('payment','P1',?,?,now(),?::vector) RETURNING id")) {
            ps.setString(1, title); ps.setString(2, body); ps.setString(3, lit(nv));
            try (ResultSet r = ps.executeQuery()) { r.next(); id = r.getLong(1); }
        }
        c.rollback();
        System.out.println("[see] 写入 #" + id + " 后回滚：");
        printSimilar(c, qv, "payment");
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO tickets(service,severity,title,body,created_at,embedding)" +
                " VALUES ('payment','P1',?,?,now(),?::vector) RETURNING id")) {
            ps.setString(1, title); ps.setString(2, body); ps.setString(3, lit(nv));
            try (ResultSet r = ps.executeQuery()) { r.next(); id = r.getLong(1); }
        }
        c.commit();
        c.setAutoCommit(true);
        System.out.println("[see] 再写一次 #" + id + " 并提交：");
        printSimilar(c, qv, "payment");
        try (Statement s = c.createStatement()) { s.execute("DELETE FROM tickets WHERE id = " + id); }  // 恢复原样，方便重跑
    }

    static void printSimilar(Connection c, float[] qv, String service) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SIMILAR)) {
            ps.setString(1, lit(qv)); ps.setString(2, service); ps.setString(3, lit(qv));
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) System.out.printf("      #%-2d %-8s %s %-22s 距离 %s%n",
                        r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5));
            }
        }
    }

    // ---------------------------------------------------------------- filter
    static void filter(Connection c) throws Exception {
        final int N = 100000, D = 128, Q = 20, K = 10;
        try (Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("DROP TABLE IF EXISTS items");
            s.execute("CREATE TABLE items (id int PRIMARY KEY, tenant int NOT NULL, kind int NOT NULL, emb vector(" + D + ") NOT NULL)");
            long t0 = System.nanoTime();
            // 真实 embedding 是“成团”的：同类文本挤在一起。这里用 200 个随机中心 + 噪声来模拟（纯均匀随机向量是 ANN 最差的情况，不像真数据）
            // tenant：1..100，每个约 1%；kind：1..10，每个约 10%；两者都和向量所在的团无关
            s.execute("SELECT setseed(0.42)");
            s.execute("DROP TABLE IF EXISTS centers");
            s.execute("CREATE TABLE centers AS SELECT c, (SELECT array_agg(random() - 0.5) FROM generate_series(1," + D + ") WHERE c >= 0) v" +
                    " FROM generate_series(0, 199) c");
            // 团、tenant、kind 各自独立随机（别用 i 取模推出来：那样 tenant 会被团决定，过滤条件和查询向量就“反相关”了）
            s.execute("INSERT INTO items SELECT g.i, g.t, g.k," +
                    " (SELECT array_agg(ce.v[j] + (random() - 0.5) * 0.4) FROM generate_series(1," + D + ") j)::vector" +
                    " FROM (SELECT i, floor(random() * 200)::int AS cc, floor(random() * 100)::int + 1 AS t, floor(random() * 10)::int + 1 AS k" +
                    "       FROM generate_series(1," + N + ") i) g JOIN centers ce ON ce.c = g.cc");
            s.execute("VACUUM ANALYZE items");
            System.out.printf("[filter] items %d 行 × %d 维，灌数 %.1f s，表 %s%n", N, D, sec(t0), size(c, "items"));
            s.execute("SET maintenance_work_mem = '256MB'");
            t0 = System.nanoTime();
            s.execute("CREATE INDEX items_hnsw ON items USING hnsw (emb vector_cosine_ops)");  // m=16, ef_construction=64
            System.out.printf("[filter] 建 HNSW（m=16, ef_construction=64）%.1f s，索引 %s%n", sec(t0), size(c, "items_hnsw"));
        }
        // 查询向量：同样从团里取 Q 个（中心 + 噪声）
        List<String> qs = new ArrayList<String>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT (SELECT array_agg(ce.v[j] + (random() - 0.5) * 0.4) FROM generate_series(1," + D + ") j)::vector::text" +
                " FROM generate_series(1," + Q + ") q JOIN centers ce ON ce.c = (q * 37) % 200")) {
            while (r.next()) qs.add(r.getString(1));
        }
        String[][] cases = {{"不过滤", "true"}, {"kind = 3（约 10%）", "kind = 3"}, {"tenant = 7（约 1%）", "tenant = 7"}};
        for (String[] cs : cases) {
            List<Set<Integer>> truth = run(c, qs, cs[1], K, "SET enable_indexscan = off", null);   // 精确：顺序扫描 + 排序
            Stat a = measure(c, qs, cs[1], K, "SET enable_indexscan = on; SET hnsw.iterative_scan = off", truth);
            Stat b = measure(c, qs, cs[1], K, "SET enable_indexscan = on; SET hnsw.iterative_scan = relaxed_order", truth);
            Stat e = measure(c, qs, cs[1], K, "SET enable_indexscan = off", truth);
            System.out.printf("[filter] %-18s 精确扫描 %5.1f ms | HNSW 平均返回 %4.1f 行（最少 %d），召回 %3.0f%%，%5.1f ms | 加 iterative_scan 返回 %4.1f 行，召回 %3.0f%%，%5.1f ms%n",
                    cs[0], e.ms, a.rows, a.minRows, a.recall * 100, a.ms, b.rows, b.recall * 100, b.ms);
        }
        try (Statement s = c.createStatement()) {
            s.execute("SET enable_indexscan = on; SET hnsw.iterative_scan = off");
            System.out.println("[filter] tenant = 7 的执行计划（只有 HNSW）：" + plan(c, qs.get(0), "tenant = 7"));
            s.execute("CREATE INDEX items_tenant ON items(tenant)");
            s.execute("ANALYZE items");
            System.out.println("[filter] 加 B-tree(tenant) 之后：            " + plan(c, qs.get(0), "tenant = 7"));
            List<Set<Integer>> truth = run(c, qs, "tenant = 7", K, "SET enable_indexscan = off; SET enable_bitmapscan = off", null);
            Stat d = measure(c, qs, "tenant = 7", K, "", truth);
            System.out.printf("[filter] 加 B-tree 后 tenant = 7：平均返回 %.1f 行，召回 %.0f%%，%.1f ms（计划没变）%n", d.rows, d.recall * 100, d.ms);
            sql = EXACT_CTE;
            System.out.println("[filter] 改写成 MATERIALIZED CTE：" + plan(c, qs.get(0), "tenant = 7"));
            Stat f = measure(c, qs, "tenant = 7", K, "", truth);
            System.out.printf("[filter] 先过滤再精确排序：平均返回 %.1f 行，召回 %.0f%%，%.1f ms%n", f.rows, f.recall * 100, f.ms);
            sql = ANN;
            s.execute("RESET ALL");
        }
    }

    static final String ANN = "SELECT id FROM items WHERE %s ORDER BY emb <=> ?::vector LIMIT %d";
    // 先用 B-tree 把这个租户的约 1000 行取出来，再精确排序：MATERIALIZED 挡住计划器把条件推回去走 HNSW
    static final String EXACT_CTE = "WITH t AS MATERIALIZED (SELECT id, emb FROM items WHERE %s) SELECT id FROM t ORDER BY emb <=> ?::vector LIMIT %d";
    static String sql = ANN;

    static class Stat { double rows, recall, ms; int minRows = Integer.MAX_VALUE; }

    static Stat measure(Connection c, List<String> qs, String where, int k, String set, List<Set<Integer>> truth) throws SQLException {
        run(c, qs, where, k, set, null);   // 预热一遍
        long t0 = System.nanoTime();
        List<Set<Integer>> got = run(c, qs, where, k, set, null);
        Stat st = new Stat();
        st.ms = (System.nanoTime() - t0) / 1e6 / qs.size();
        for (int i = 0; i < got.size(); i++) {
            Set<Integer> g = got.get(i);
            st.rows += g.size();
            st.minRows = Math.min(st.minRows, g.size());
            int hit = 0;
            for (Integer id : g) if (truth.get(i).contains(id)) hit++;
            st.recall += hit / (double) truth.get(i).size();
        }
        st.rows /= got.size();
        st.recall /= got.size();
        return st;
    }

    static List<Set<Integer>> run(Connection c, List<String> qs, String where, int k, String set, Object unused) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute("RESET ALL; " + set); }
        List<Set<Integer>> out = new ArrayList<Set<Integer>>();
        try (PreparedStatement ps = c.prepareStatement(String.format(sql, where, k))) {
            for (String q : qs) {
                ps.setString(1, q);
                Set<Integer> ids = new HashSet<Integer>();
                try (ResultSet r = ps.executeQuery()) { while (r.next()) ids.add(r.getInt(1)); }
                out.add(ids);
            }
        }
        return out;
    }

    static String plan(Connection c, String q, String where) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN (COSTS OFF) " + String.format(sql, where, 10))) {
            ps.setString(1, q);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String line = r.getString(1).trim().replace("->  ", "");
                    if (line.startsWith("Order By") || line.startsWith("Sort Key")) continue;
                    if (sb.length() > 0) sb.append(" → ");
                    sb.append(line);
                }
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 工具
    static String lit(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }

    static double sec(long t0) { return (System.nanoTime() - t0) / 1e9; }

    static String size(Connection c, String rel) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT pg_size_pretty(pg_relation_size('" + rel + "'))")) {
            r.next(); return r.getString(1);
        }
    }

    /** 调 ollama /api/embed；失败就退回字二元组哈希向量。 */
    static List<float[]> embed(List<String> texts) {
        if (!fake) {
            try { return ollama(texts); } catch (Exception e) {
                System.out.println("      （ollama 不可用：" + e.getMessage() + "，改用字二元组哈希向量）");
                fake = true;
            }
        }
        List<float[]> out = new ArrayList<float[]>();
        for (String t : texts) {
            float[] v = new float[DIM];
            for (int i = 0; i + 1 < t.length(); i++) v[Math.floorMod(t.substring(i, i + 2).hashCode(), DIM)] += 1;
            double n = 0; for (float x : v) n += x * x; n = Math.sqrt(n);
            for (int i = 0; i < DIM; i++) v[i] /= n;
            out.add(v);
        }
        return out;
    }

    static List<float[]> ollama(List<String> texts) throws Exception {
        StringBuilder body = new StringBuilder("{\"model\":\"bge-m3\",\"input\":[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) body.append(',');
            body.append('"').append(texts.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        body.append("]}");
        HttpURLConnection h = (HttpURLConnection) new URL(OLLAMA + "/api/embed").openConnection();
        h.setConnectTimeout(2000); h.setReadTimeout(120000);
        h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = h.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        if (h.getResponseCode() != 200) throw new IllegalStateException("HTTP " + h.getResponseCode());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
        String json = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        // 响应形如 {"model":"bge-m3","embeddings":[[0.01,-0.02,…],[…]],…}；不引 JSON 库，手切
        int p = json.indexOf("\"embeddings\":[") + "\"embeddings\":[".length();
        List<float[]> out = new ArrayList<float[]>();
        while (json.charAt(p) == '[') {
            int e = json.indexOf(']', p);
            String[] parts = json.substring(p + 1, e).split(",");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i]);
            out.add(v);
            p = e + 1;
            if (json.charAt(p) == ',') p++;
        }
        return out;
    }
}
