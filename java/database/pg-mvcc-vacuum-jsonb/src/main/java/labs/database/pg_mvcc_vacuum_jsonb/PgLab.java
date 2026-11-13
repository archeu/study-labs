package labs.database.pg_mvcc_vacuum_jsonb;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 的多版本、VACUUM、索引类型和 JSONB。按 JDK 8 写法。
 * 每一步一个参数：setup / see / bloat / longtx / hot / index（run.sh 依次调用）。
 * 为了让数字可复现，orders 关掉了 autovacuum（autovacuum_enabled=off），VACUUM 都是手动发的。
 */
public class PgLab {
    static final String URL = "jdbc:postgresql://127.0.0.1:55418/postgres";

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, "postgres", "pg");
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "setup": setup(); break;
            case "see": see(); break;
            case "bloat": bloat(); break;
            case "longtx": longtx(); break;
            case "hot": hot(); break;
            case "index": index(); break;
            default: throw new IllegalArgumentException(args[0]);
        }
    }

    // ---------- setup：100 万行 orders，和 MySQL 那几页同一份数据，多一列 ext jsonb ----------
    static void setup() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pageinspect");
            s.execute("CREATE EXTENSION IF NOT EXISTS pgstattuple");
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("CREATE TABLE orders ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " order_no VARCHAR(20) NOT NULL,"
                    + " user_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, qty INT NOT NULL,"
                    + " amount_cents BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
                    + " created_at TIMESTAMP(6) NOT NULL, paid_at TIMESTAMP(6) NULL,"
                    + " ext JSONB NOT NULL) WITH (autovacuum_enabled = off)");
            long t0 = System.nanoTime();
            s.execute("INSERT INTO orders SELECT i, '20261101' || lpad(i::text, 8, '0'), 1 + i * 7919 % 200000, 1 + i % 500,"
                    + " 1 + i % 5, (i % 1000 + 1) * 100, 'CREATED', timestamp '2026-11-01' + i * interval '1 second', NULL,"
                    + " jsonb_strip_nulls(jsonb_build_object("
                    + "   'channel', (ARRAY['app','web','wechat','alipay','mini'])[1 + i % 5],"
                    + "   'coupon', CASE WHEN i % 10 = 0 THEN jsonb_build_object('code', 'C' || (i % 97)) END,"
                    + "   'tags', CASE WHEN i % 100 = 0 THEN '[\"vip\"]'::jsonb ELSE '[]'::jsonb END))"
                    + " FROM generate_series(1::bigint, 1000000) i");
            s.execute("CREATE INDEX idx_orders_user ON orders (user_id)");
            s.execute("VACUUM ANALYZE orders");
            System.out.printf("[setup] 100 万行，%.1f s | %s%n", (System.nanoTime() - t0) / 1e9, sizes(s));
            System.out.println("        " + one(s, "SELECT version()"));
        }
    }

    // ---------- see：⑤ 一条付款 UPDATE，旧版本留在表里 ----------
    static void see() throws SQLException {
        try (Connection a = conn(); Connection b = conn(); Statement sa = a.createStatement(); Statement sb = b.createStatement()) {
            String q = "SELECT ctid, xmin, xmax, status FROM orders WHERE id = 9527";
            System.out.println("[see] 改之前      " + row(sb, q));
            String[] old = one(sb, "SELECT ctid FROM orders WHERE id = 9527").replaceAll("[()]", "").split(",");
            int oldPage = Integer.parseInt(old[0]), oldSlot = Integer.parseInt(old[1]);
            a.setAutoCommit(false);
            sa.executeUpdate("UPDATE orders SET status = 'PAID', paid_at = now() WHERE id = 9527");
            System.out.println("[see] 付款事务 xid=" + one(sa, "SELECT pg_current_xact_id()") + "，UPDATE 后、未提交：");
            System.out.println("      付款连接读到   " + row(sa, q));
            System.out.println("      另一个连接读到 " + row(sb, q));
            String[] nw = one(sa, "SELECT ctid FROM orders WHERE id = 9527").replaceAll("[()]", "").split(",");
            int newPage = Integer.parseInt(nw[0]), newSlot = Integer.parseInt(nw[1]);
            System.out.println("      两个版本都在表里（lp_flags 0=空闲 1=正常 2=重定向 3=死；get_raw_page 直接读页，不会触发清理）：");
            dumpSlot(sb, oldPage, oldSlot);
            dumpSlot(sb, newPage, newSlot);
            System.out.println("      新版本那一页有 " + one(sb, "SELECT count(*) FROM heap_page_items(get_raw_page('orders', " + newPage + ")) WHERE lp_flags = 1")
                    + " 个元组；表一共 " + lng(sb, "SELECT pg_relation_size('orders') / 8192") + " 页（原来的页都满了，新版本只能去表尾）");
            a.commit();
            a.setAutoCommit(true);
            System.out.println("[see] COMMIT 之后，还没有人读过页 " + oldPage + "：");
            dumpSlot(sb, oldPage, oldSlot);
            System.out.println("[see] 另一个连接读一次 9527：" + row(sb, q) + "，再看旧槽位：");
            dumpSlot(sb, oldPage, oldSlot);
            System.out.println("      pgstattuple：" + deadStat(sb));
            System.out.println("[see] VACUUM：" + vacuum(sb, "VACUUM (VERBOSE) orders"));
            dumpSlot(sb, oldPage, oldSlot);
        }
    }

    static void dumpSlot(Statement s, int page, int slot) throws SQLException {
        System.out.println("        (" + page + "," + slot + ") " + row(s, "SELECT lp_flags, t_xmin, t_xmax, t_ctid FROM heap_page_items(get_raw_page('orders', "
                + page + ")) WHERE lp = " + slot));
    }

    // ---------- bloat：整表改一遍 ----------
    static void bloat() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            System.out.println("[bloat] 起点        " + sizes(s));
            long t0 = System.nanoTime();
            int n = s.executeUpdate("UPDATE orders SET qty = qty + 1");
            System.out.printf("[bloat] 整表 UPDATE %d 行 %.1f s → %s | %s%n", n, (System.nanoTime() - t0) / 1e9, sizes(s), deadStat(s));
            t0 = System.nanoTime();
            String v = vacuum(s, "VACUUM (VERBOSE) orders");
            System.out.printf("[bloat] VACUUM %.1f s → %s | %s%n", (System.nanoTime() - t0) / 1e9, sizes(s), deadStat(s));
            System.out.println("        " + v);
            t0 = System.nanoTime();
            s.executeUpdate("UPDATE orders SET qty = qty + 1");
            System.out.printf("[bloat] 再整表 UPDATE 一遍 %.1f s → %s | %s%n", (System.nanoTime() - t0) / 1e9, sizes(s), deadStat(s));
            s.execute("VACUUM orders");
            t0 = System.nanoTime();
            s.execute("VACUUM FULL orders");
            System.out.printf("[bloat] VACUUM FULL %.1f s → %s%n", (System.nanoTime() - t0) / 1e9, sizes(s));
            s.execute("VACUUM ANALYZE orders");
        }
    }

    // ---------- longtx：一个开着的快照让 VACUUM 删不掉 ----------
    static void longtx() throws Exception {
        try (Connection r = conn(); Connection w = conn(); Connection fresh = conn();
             Statement sr = r.createStatement(); Statement sw = w.createStatement(); Statement sf = fresh.createStatement()) {
            r.setAutoCommit(false);
            r.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            String q = "SELECT qty FROM orders WHERE id = 9527";
            System.out.println("[longtx] 报表事务开始（REPEATABLE READ），读到 qty=" + one(sr, q));
            String q2 = "SELECT qty FROM orders WHERE id = 9528";
            sf.execute("SET enable_seqscan = off"); // 让新连接每次都走主键索引，和 MySQL 页一样按主键等值读
            System.out.printf("[longtx] 改之前新连接读 9527：%.3f ms，%s%n", timeQuery(sf, q, 200), plan(sf, q));
            long t0 = System.nanoTime();
            for (int i = 0; i < 20000; i++) sw.executeUpdate("UPDATE orders SET qty = qty + 1 WHERE id = 9527");
            System.out.printf("[longtx] 9527 被改了 20000 次（每次自动提交），%.1f s%n", (System.nanoTime() - t0) / 1e9);
            System.out.println("[longtx] 报表事务读 9527：qty=" + one(sr, q) + String.format("，%.3f ms，", timeQuery(sr, q, 200)) + plan(sr, q));
            System.out.println("[longtx] 新连接读 9527：qty=" + one(sf, q) + String.format("，%.3f ms，", timeQuery(sf, q, 200)) + plan(sf, q));
            System.out.printf("[longtx] 新连接读隔壁 9528：%.3f ms%n", timeQuery(sf, q2, 200));
            System.out.println("          表里 9527 的版本数（pageinspect 按 id 数）：" + versions(sf));
            System.out.println("          报表事务的 backend_xmin 落后当前 xid："
                    + one(sf, "SELECT age(backend_xmin) FROM pg_stat_activity WHERE backend_xmin IS NOT NULL ORDER BY age(backend_xmin) DESC LIMIT 1"));
            System.out.println("[longtx] 报表事务开着时 VACUUM：" + vacuum(sf, "VACUUM (VERBOSE) orders"));
            System.out.printf("          VACUUM 后新连接读 9527：%.3f ms，%s%n", timeQuery(sf, q, 200), plan(sf, q));
            r.commit();
            System.out.println("[longtx] 报表事务提交后 VACUUM：" + vacuum(sf, "VACUUM (VERBOSE) orders"));
            System.out.printf("          新连接读 9527：%.3f ms，%s | 9527 的版本数：%s | %s%n", timeQuery(sf, q, 200), plan(sf, q), versions(sf), sizes(sf));
            sf.execute("VACUUM FULL orders");
            sf.execute("VACUUM ANALYZE orders");
        }
    }

    static String versions(Statement s) throws SQLException {
        // 扫全表每一页，数 t_data 前 8 字节等于 9527 的元组（id 是第一列）；死的、活的、还没清的都算
        return one(s, "SELECT count(*) FROM generate_series(0, pg_relation_size('orders') / 8192 - 1) blk,"
                + " heap_page_items(get_raw_page('orders', blk::int)) h"
                + " WHERE h.t_data IS NOT NULL AND substring(h.t_data from 1 for 8) = '\\x3725000000000000'::bytea");  // 9527 = 0x2537，小端存储（x86 / ARM 都是）
    }

    // ---------- hot：fillfactor 100 对 90 ----------
    static void hot() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            for (int ff : new int[]{100, 90}) {
                String t = "o" + ff;
                s.execute("DROP TABLE IF EXISTS " + t);
                s.execute("CREATE TABLE " + t + " (LIKE orders INCLUDING ALL) WITH (fillfactor = " + ff + ", autovacuum_enabled = off)");
                s.execute("INSERT INTO " + t + " SELECT * FROM orders WHERE id <= 100000");
                s.execute("VACUUM ANALYZE " + t);
                long heap0 = lng(s, "SELECT pg_relation_size('" + t + "')");
                long pk0 = lng(s, "SELECT pg_relation_size('" + t + "_pkey')");
                long ui0 = lng(s, "SELECT pg_relation_size('" + t + "_user_id_idx')");
                c.setAutoCommit(false);
                // 每 10 行改一行 status（不在任何索引里）
                s.executeUpdate("UPDATE " + t + " SET status = 'PAID', paid_at = now() WHERE id % 10 = 0");
                String st = row(s, "SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_xact_user_tables WHERE relname = '" + t + "'");
                c.commit();
                c.setAutoCommit(true);
                System.out.printf("[hot] fillfactor %3d：改 status 1 万行 → %s | 表 %s→%s | 主键索引 %s→%s | user_id 索引 %s→%s%n",
                        ff, st, kb(heap0), kb(lng(s, "SELECT pg_relation_size('" + t + "')")),
                        kb(pk0), kb(lng(s, "SELECT pg_relation_size('" + t + "_pkey')")),
                        kb(ui0), kb(lng(s, "SELECT pg_relation_size('" + t + "_user_id_idx')")));
                c.setAutoCommit(false);
                s.executeUpdate("UPDATE " + t + " SET user_id = user_id + 1 WHERE id % 10 = 1");
                st = row(s, "SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_xact_user_tables WHERE relname = '" + t + "'");
                c.commit();
                c.setAutoCommit(true);
                System.out.printf("[hot] fillfactor %3d：再改 user_id 1 万行（索引列）→ 累计 %s | user_id 索引 → %s%n", ff, st,
                        kb(lng(s, "SELECT pg_relation_size('" + t + "_user_id_idx')")));
                s.execute("DROP TABLE " + t);
            }
        }
    }

    // ---------- index：JSONB + GIN、表达式索引、BRIN、部分索引 ----------
    static void index() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("DROP INDEX IF EXISTS gin_ops, gin_path, idx_coupon, bt_created, brin_created, idx_unpaid");
            System.out.println("[index] ext 长这样：" + one(s, "SELECT ext FROM orders WHERE id = 9520"));
            String contain = "SELECT count(*) FROM orders WHERE ext @> '{\"coupon\":{\"code\":\"C7\"}}'";
            String arrow = "SELECT count(*) FROM orders WHERE ext -> 'coupon' ->> 'code' = 'C7'";
            System.out.printf("[index] 没有索引：@> %.1f ms，%s%n", timeQuery(s, contain, 3), plan(s, contain));
            long t0 = System.nanoTime();
            s.execute("CREATE INDEX gin_ops ON orders USING gin (ext)");
            System.out.printf("[index] GIN jsonb_ops 建索引 %.1f s，%s%n", (System.nanoTime() - t0) / 1e9, mb(lng(s, "SELECT pg_relation_size('gin_ops')")));
            t0 = System.nanoTime();
            s.execute("CREATE INDEX gin_path ON orders USING gin (ext jsonb_path_ops)");
            System.out.printf("[index] GIN jsonb_path_ops 建索引 %.1f s，%s%n", (System.nanoTime() - t0) / 1e9, mb(lng(s, "SELECT pg_relation_size('gin_path')")));
            s.execute("ANALYZE orders");
            System.out.printf("[index] 有 GIN：@> %.2f ms，%s%n", timeQuery(s, contain, 5), plan(s, contain));
            System.out.printf("[index] 有 GIN：->> = %.1f ms，%s%n", timeQuery(s, arrow, 3), plan(s, arrow));
            String hasKey = "SELECT count(*) FROM orders WHERE ext ? 'coupon'";
            System.out.printf("[index] 有 GIN：? 'coupon'（10%% 的行）%.1f ms，%s%n", timeQuery(s, hasKey, 3), plan(s, hasKey));
            s.execute("CREATE INDEX idx_coupon ON orders ((ext -> 'coupon' ->> 'code'))");
            s.execute("ANALYZE orders");
            System.out.printf("[index] 表达式索引 %s 之后：->> = %.2f ms，%s%n", mb(lng(s, "SELECT pg_relation_size('idx_coupon')")), timeQuery(s, arrow, 5), plan(s, arrow));
            s.execute("DROP INDEX gin_ops, gin_path, idx_coupon");

            String range = "SELECT count(*) FROM orders WHERE created_at >= '2026-11-05 10:00' AND created_at < '2026-11-05 11:00'";
            System.out.printf("[index] 没有索引：一小时内的订单 %.1f ms，%s%n", timeQuery(s, range, 3), plan(s, range));
            s.execute("CREATE INDEX bt_created ON orders (created_at)");
            s.execute("ANALYZE orders");
            System.out.printf("[index] B-tree(created_at) %s：%.2f ms，%s%n", mb(lng(s, "SELECT pg_relation_size('bt_created')")), timeQuery(s, range, 5), plan(s, range));
            s.execute("DROP INDEX bt_created");
            s.execute("CREATE INDEX brin_created ON orders USING brin (created_at)");
            s.execute("ANALYZE orders");
            System.out.printf("[index] BRIN(created_at) %s：%.2f ms，%s%n", kb(lng(s, "SELECT pg_relation_size('brin_created')")), timeQuery(s, range, 5), plan(s, range));
            s.execute("DROP INDEX brin_created");

            s.executeUpdate("UPDATE orders SET status = 'PAID', paid_at = created_at + interval '1 minute' WHERE id % 100 <> 0");
            s.execute("VACUUM FULL orders");
            s.execute("ANALYZE orders");
            String unpaid = "SELECT id FROM orders WHERE status = 'CREATED' AND user_id = 191901"; // id=100 的用户，有一笔待付款
            s.execute("CREATE INDEX idx_unpaid ON orders (user_id) WHERE status = 'CREATED'");
            s.execute("ANALYZE orders");
            System.out.printf("[index] 部分索引 WHERE status='CREATED'（1%% 的行）%s，对比全量 user_id 索引 %s：%.2f ms，%s%n",
                    kb(lng(s, "SELECT pg_relation_size('idx_unpaid')")), mb(lng(s, "SELECT pg_relation_size('idx_orders_user')")),
                    timeQuery(s, unpaid, 5), plan(s, unpaid));
            s.execute("DROP INDEX idx_unpaid");
        }
    }

    // ---------- 小工具 ----------
    static String vacuum(Statement s, String sql) throws SQLException {
        s.execute(sql);
        List<String> out = new ArrayList<>();
        for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
            for (String line : w.getMessage().split("\n")) {
                String l = line.trim();
                if (l.startsWith("pages:") && !out.isEmpty()) break; // 后面是 TOAST 表的那一段
                if (l.startsWith("tuples:") || l.startsWith("removable cutoff") || (l.startsWith("pages:") && out.isEmpty())
                        || l.startsWith("index scan") || l.startsWith("index \"")) out.add(l);
            }
        }
        s.clearWarnings();
        return String.join(" | ", out);
    }

    static String sizes(Statement s) throws SQLException {
        return "表 " + mb(lng(s, "SELECT pg_relation_size('orders')"))
                + "，主键索引 " + mb(lng(s, "SELECT pg_relation_size('orders_pkey')"))
                + "，user_id 索引 " + mb(lng(s, "SELECT pg_relation_size('idx_orders_user')"));
    }

    static String deadStat(Statement s) throws SQLException {
        return row(s, "SELECT tuple_count AS live, dead_tuple_count AS dead, round(free_percent::numeric, 1) AS free_pct FROM pgstattuple('orders')");
    }

    static double timeQuery(Statement s, String sql, int n) throws SQLException {
        one(s, sql); // 预热
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) one(s, sql);
        return (System.nanoTime() - t0) / 1e6 / n;
    }

    static String plan(Statement s, String sql) throws SQLException {
        List<String> nodes = new ArrayList<>();
        try (ResultSet r = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, SUMMARY OFF) " + sql)) {
            while (r.next()) {
                String l = r.getString(1).trim().replaceFirst("^->\\s*", "");
                if (l.startsWith("Buffers:") || l.contains("Scan") || l.startsWith("Rows Removed") || l.startsWith("Heap Blocks"))
                    nodes.add(l.replaceAll("\\(cost=[^)]*\\)\\s*", ""));
            }
        }
        return String.join(" / ", nodes);
    }

    static String one(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    static long lng(Statement s, String sql) throws SQLException {
        return Long.parseLong(one(s, sql));
    }

    static String row(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) return "(无)";
            int n = r.getMetaData().getColumnCount();
            StringBuilder b = new StringBuilder();
            for (int i = 1; i <= n; i++) b.append(i > 1 ? " " : "").append(r.getMetaData().getColumnLabel(i)).append('=').append(r.getString(i));
            return b.toString();
        }
    }

    static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    static String kb(long bytes) {
        return String.format("%d KB", bytes / 1024);
    }
}
