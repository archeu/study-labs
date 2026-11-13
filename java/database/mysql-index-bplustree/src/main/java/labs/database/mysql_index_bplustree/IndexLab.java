package labs.database.mysql_index_bplustree;

import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;

/**
 * 交易靶场 orders 表（100 万行，和全景 J06 同一份数据），亲眼看 B+ 树：
 *   setup              建表、灌数、ANALYZE
 *   shape <orders.ibd>  直接读表空间文件，数每棵索引树每一层有几页、几条记录
 *   reads              几种查询各读了几个页（Innodb_buffer_pool_read_requests 的增量）
 *   prefix             加联合索引 (user_id, created_at)，看最左前缀哪些用得上
 * 由 run.sh 调用；JDBC 地址默认 127.0.0.1:33071，可用 -Durl=... 覆盖。
 * 按 JDK 8 写法（--release 8）。
 */
public class IndexLab {
    static final String URL = System.getProperty("url",
            "jdbc:mysql://127.0.0.1:33071/?user=root&password=root&allowPublicKeyRetrieval=true&useSSL=false");

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "reads";
        if ("setup".equals(cmd)) setup();
        else if ("shape".equals(cmd)) shape(args[1]);
        else if ("reads".equals(cmd)) reads();
        else if ("prefix".equals(cmd)) prefix();
        else throw new IllegalArgumentException(cmd);
    }

    // ------------------------------------------------------------------ setup
    static void setup() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS trade");
            s.execute("USE trade");
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("CREATE TABLE orders ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " user_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, qty INT NOT NULL,"
                    + " amount_cents BIGINT NOT NULL, status VARCHAR(16) NOT NULL,"
                    + " created_at DATETIME(6) NOT NULL, paid_at DATETIME(6) NULL,"
                    + " KEY idx_user (user_id)) ENGINE=InnoDB");
            s.execute("SET SESSION cte_max_recursion_depth = 1000000");
            long t0 = System.nanoTime();
            for (int b = 0; b < 10; b++) {           // 100 万行，按主键递增分 10 批插
                int o = b * 100000;
                s.execute("INSERT INTO orders WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100000)"
                        + " SELECT " + o + " + i, 1 + (" + o + " + i) * 7919 % 200000, 1 + (" + o + " + i) % 500,"
                        + " 1 + (" + o + " + i) % 3, 1000 + (" + o + " + i) * 37 % 99000,"
                        + " IF((" + o + " + i) % 10 = 0, 'CREATED', 'PAID'),"
                        + " TIMESTAMP('2026-11-01') + INTERVAL (" + o + " + i) SECOND, NULL FROM n");
            }
            // 场景里那一单：用户 1024 买了 2 件 88 号商品，待付款
            s.execute("UPDATE orders SET user_id=1024, sku_id=88, qty=2, amount_cents=79800, status='CREATED' WHERE id=9527");
            s.execute("ANALYZE TABLE orders");
            System.out.printf("[setup] 100 万行，用时 %.1f s%n", (System.nanoTime() - t0) / 1e9);
        }
    }

    // ------------------------------------------------------------------ shape
    // InnoDB 页（16 KB）：FIL 头 38 字节里，偏移 24 是页类型（17855 = INDEX）；
    // 紧跟着的页头里，偏移 38+16 是 PAGE_N_RECS，38+26 是 PAGE_LEVEL（叶子为 0），38+28 是 PAGE_INDEX_ID。
    static void shape(String ibd) throws Exception {
        Map<Long, String> names = new TreeMap<Long, String>();
        Map<Long, Long> roots = new TreeMap<Long, Long>();
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT i.INDEX_ID, i.NAME, i.PAGE_NO FROM information_schema.INNODB_INDEXES i"
                     + " JOIN information_schema.INNODB_TABLES t USING (TABLE_ID) WHERE t.NAME='trade/orders'")) {
            while (r.next()) { names.put(r.getLong(1), r.getString(2)); roots.put(r.getLong(1), r.getLong(3)); }
        }
        // key = indexId * 100 + level  ->  {pages, records}
        Map<Long, long[]> stat = new TreeMap<Long, long[]>();
        byte[] p = new byte[16384];
        long pageNo = 0;
        try (RandomAccessFile f = new RandomAccessFile(ibd, "r")) {
            while (f.read(p) == p.length) {
                if (u16(p, 24) == 17855) {
                    long id = u64(p, 38 + 28);
                    int level = u16(p, 38 + 26);
                    long[] a = stat.get(id * 100 + level);
                    if (a == null) stat.put(id * 100 + level, a = new long[2]);
                    a[0]++; a[1] += u16(p, 38 + 16);
                }
                pageNo++;
            }
        }
        System.out.printf("[shape] %s：%d 个页 × 16 KB%n", ibd, pageNo);
        long last = -1;
        for (Map.Entry<Long, long[]> e : ((TreeMap<Long, long[]>) stat).descendingMap().entrySet()) {
            long id = e.getKey() / 100; int level = (int) (e.getKey() % 100);
            if (id != last) {
                System.out.printf("  %s（根页 %d）%n", names.get(id), roots.get(id));
                last = id;
            }
            long[] a = e.getValue();
            System.out.printf("    第 %d 层%s：%6d 页  %8d 条记录  平均每页 %5.0f 条%n",
                    level, level == 0 ? "（叶子）" : "        ", a[0], a[1], (double) a[1] / a[0]);
        }
    }

    static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    static long u64(byte[] b, int o) { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xff); return v; }

    // ------------------------------------------------------------------ reads
    static void reads() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("USE trade");
            measure(s, "① 主键等值", "SELECT * FROM orders WHERE id = 9527");
            measure(s, "② 二级索引 + 回表", "SELECT * FROM orders WHERE user_id = 1024");
            measure(s, "③ 覆盖索引", "SELECT id, user_id FROM orders WHERE user_id = 1024");
            measure(s, "④ 没索引：全表扫", "SELECT * FROM orders WHERE sku_id = 88 AND qty = 2");
            measure(s, "⑤ 10 万行：走 idx_user + 回表", "SELECT SUM(amount_cents) FROM orders WHERE user_id BETWEEN 1 AND 20000");
            measure(s, "⑥ 10 万行：不用索引，全表扫", "SELECT SUM(amount_cents) FROM orders IGNORE INDEX (idx_user) WHERE user_id BETWEEN 1 AND 20000");
            measure(s, "⑦ 同一范围但只要索引里有的列", "SELECT COUNT(*) FROM orders WHERE user_id BETWEEN 1 AND 20000");
        }
    }

    static long status(Statement s) throws Exception {
        try (ResultSet r = s.executeQuery("SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read_requests'")) {
            r.next(); return r.getLong(2);
        }
    }

    /** 先跑一次把页读进 buffer pool，再跑 5 次取最小的逻辑读增量和耗时；同时打出 EXPLAIN。 */
    static void measure(Statement s, String label, String sql) throws Exception {
        runAll(s, sql);
        long best = Long.MAX_VALUE, bestNs = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long before = status(s), t0 = System.nanoTime();
            runAll(s, sql);
            long ns = System.nanoTime() - t0, d = status(s) - before;
            best = Math.min(best, d); bestNs = Math.min(bestNs, ns);
        }
        System.out.printf("%-28s 读页 %7d 次  %8.2f ms   %s%n", label, best, bestNs / 1e6, explain(s, sql));
    }

    static void runAll(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery(sql)) { while (r.next()) { /* 读完 */ } }
    }

    static String explain(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery("EXPLAIN " + sql)) {
            r.next();
            return "type=" + r.getString("type") + " key=" + r.getString("key") + " rows=" + r.getString("rows")
                    + (r.getString("Extra") == null ? "" : " Extra=" + r.getString("Extra"));
        }
    }

    // ------------------------------------------------------------------ prefix
    static void prefix() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("USE trade");
            try (ResultSet r = s.executeQuery("SHOW INDEX FROM orders WHERE Key_name='idx_user_created'")) {
                if (r.next()) s.execute("ALTER TABLE orders DROP INDEX idx_user_created");   // 上次中断留下的
            }
            String page = "SELECT * FROM orders WHERE user_id = 1024 ORDER BY created_at DESC LIMIT 10";
            plan(s, "只有 idx_user：我的订单按时间倒序", page);
            long t0 = System.nanoTime();
            s.execute("ALTER TABLE orders ADD INDEX idx_user_created (user_id, created_at)");
            System.out.printf("[prefix] 加联合索引 idx_user_created(user_id, created_at)：%.1f s；"
                    + "idx_user 成了它的前缀，先设成 INVISIBLE 让优化器看不见%n", (System.nanoTime() - t0) / 1e9);
            s.execute("ALTER TABLE orders ALTER INDEX idx_user INVISIBLE");
            try {
                plan(s, "我的订单按时间倒序", page);
                plan(s, "user_id 等值", "SELECT * FROM orders WHERE user_id = 1024");
                plan(s, "user_id 等值 + created_at 范围", "SELECT * FROM orders WHERE user_id = 1024 AND created_at >= '2026-11-01 02:00:00'");
                plan(s, "只有 created_at（缺最左列）", "SELECT * FROM orders WHERE created_at = '2026-11-01 02:38:47'");
                plan(s, "user_id 范围 + created_at 等值", "SELECT * FROM orders WHERE user_id BETWEEN 1024 AND 1100 AND created_at = '2026-11-01 02:38:47'");
            } finally {   // 还原，方便 ./run.sh again 重跑
                s.execute("ALTER TABLE orders ALTER INDEX idx_user VISIBLE");
                s.execute("ALTER TABLE orders DROP INDEX idx_user_created");
            }
        }
    }

    static void plan(Statement s, String label, String sql) throws Exception {
        try (ResultSet r = s.executeQuery("EXPLAIN " + sql)) {
            r.next();
            System.out.printf("%-26s type=%-5s key=%-16s key_len=%-4s rows=%-6s Extra=%s%n", label,
                    r.getString("type"), r.getString("key"), r.getString("key_len"), r.getString("rows"), r.getString("Extra"));
        }
    }
}
