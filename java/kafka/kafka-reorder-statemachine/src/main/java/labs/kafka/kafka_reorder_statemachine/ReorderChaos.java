package labs.kafka.kafka_reorder_statemachine;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1000 个订单的事件流，同时乱序、重复、并发、丢一条，交给四种下游写法，看最后错几单。
 *
 * 每单：seq 1 CREATED，seq 2 PAID（+积分），30% 的单还有 seq 3 REFUNDED（−积分）。
 * 混乱：30% 的单内部顺序打乱（加分区、重试 topic）；15% 的事件重复一次；
 *       再挑 10% 的事件在稍后重投一次（重平衡）；1% 的退款单丢了 PAID（卡在别处）；
 *       两个线程从同一个队列抢着处理（重平衡时新旧消费者重叠、线程池）。
 * 积分表有 CHECK (points >= 0)：积分不能先扣后加。
 *
 * S1 去重表 + 状态直接覆盖
 * S2 第一圈的写法：状态用版本守卫（只升不降），积分只去重
 * S3 本页：状态机 + 连续序号 + 跳号暂存，提交时 CAS（WHERE last_seq = 旧值），不加行锁
 * S4 S3 + 先锁住这一单的行（SELECT … FOR UPDATE），暂存和放行在同一把锁下
 * S3、S4 跑完以后，对还有暂存的单做“回查”：从订单服务补拉缺的事件。
 */
public class ReorderChaos {

    static final int ORDERS = 1000;
    static final String[] TYPES = {"", "CREATED", "PAID", "REFUNDED"};

    static final class Ev {
        final int order, seq, amt;
        Ev(int order, int seq, int amt) { this.order = order; this.seq = seq; this.amt = amt; }
        String type() { return TYPES[seq]; }
        long id() { return order * 10L + seq; }
    }

    // ---------------- 造事件流 ----------------
    static final Map<Integer, List<Ev>> TRUTH = new TreeMap<>();
    static final Set<Integer> LOST = new TreeSet<>();
    static int reordered, dups, redelivered;

    static List<Ev> buildStream(long seed) {
        Random r = new Random(seed);
        List<Deque<Ev>> perOrder = new ArrayList<>();
        for (int o = 1; o <= ORDERS; o++) {
            int amt = 100 + r.nextInt(900);
            List<Ev> t = new ArrayList<>();
            t.add(new Ev(o, 1, amt));
            t.add(new Ev(o, 2, amt));
            if (r.nextDouble() < 0.3) t.add(new Ev(o, 3, amt));
            TRUTH.put(o, t);
            List<Ev> s = new ArrayList<>(t);
            if (t.size() == 3 && r.nextDouble() < 0.033) { s.remove(1); LOST.add(o); }   // 约 1% 的单
            if (r.nextDouble() < 0.3) { Collections.shuffle(s, r); reordered++; }
            List<Ev> withDup = new ArrayList<>();
            for (Ev e : s) {
                withDup.add(e);
                if (r.nextDouble() < 0.15) { withDup.add(e); dups++; }
            }
            perOrder.add(new ArrayDeque<>(withDup));
        }
        // 各单交错：每次随机挑一个还没发完的单，发它的下一条
        List<Ev> stream = new ArrayList<>();
        List<Deque<Ev>> live = new ArrayList<>(perOrder);
        while (!live.isEmpty()) {
            int i = r.nextInt(Math.min(live.size(), 40));     // 同时“在途”的约 40 单，同一单的事件挨得近
            Deque<Ev> q = live.get(i);
            stream.add(q.poll());
            if (q.isEmpty()) live.remove(i);
        }
        // 重平衡：10% 的事件在后面 1～50 条之内再投一次
        int n = stream.size();
        List<Ev> out = new ArrayList<>(stream);
        for (int k = 0; k < n / 10; k++) {
            int from = r.nextInt(n);
            int to = Math.min(out.size(), from + 1 + r.nextInt(50));
            out.add(to, stream.get(from));
            redelivered++;
        }
        return out;
    }

    // ---------------- 四种写法 ----------------
    interface Handler { void handle(Connection c, Ev e) throws SQLException; }

    static final class Stats {
        final AtomicInteger givenUp = new AtomicInteger(), casConflicts = new AtomicInteger(),
                parked = new AtomicInteger(), skippedStaleSms = new AtomicInteger(), illegal = new AtomicInteger();
    }

    static boolean isDup(SQLException ex) { return "23505".equals(ex.getSQLState()); }
    static boolean isCheck(SQLException ex) { return "23513".equals(ex.getSQLState()); }

    static void addPoints(Connection c, int order, int delta) throws SQLException {
        try (PreparedStatement u = c.prepareStatement("UPDATE order_points SET points = points + ? WHERE order_id = ?")) {
            u.setInt(1, delta); u.setInt(2, order);
            if (u.executeUpdate() == 0) {
                try (PreparedStatement i = c.prepareStatement("INSERT INTO order_points VALUES (?, ?)")) {
                    i.setInt(1, order); i.setInt(2, delta); i.executeUpdate();
                }
            }
        }
    }

    static void sms(Connection c, int order, String kind) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO sms(order_id, kind) VALUES (?, ?)")) {
            p.setInt(1, order); p.setString(2, kind); p.executeUpdate();
        }
    }

    static int delta(Ev e) { return e.seq == 2 ? e.amt : e.seq == 3 ? -e.amt : 0; }

    /** 去重：插一行，主键冲突说明处理过了 */
    static boolean firstTime(Connection c, Ev e) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO dedup VALUES (?)")) {
            p.setLong(1, e.id()); p.executeUpdate(); return true;
        } catch (SQLException ex) {
            if (isDup(ex)) return false;
            throw ex;
        }
    }

    // S1：去重表 + 直接覆盖
    static Handler s1(Stats st) {
        return (c, e) -> {
            if (!firstTime(c, e)) return;
            try (PreparedStatement p = c.prepareStatement("MERGE INTO order_view KEY(order_id) VALUES (?, ?, ?)")) {
                p.setInt(1, e.order); p.setString(2, e.type()); p.setInt(3, e.seq); p.executeUpdate();
            }
            if (e.seq > 1) { addPoints(c, e.order, delta(e)); sms(c, e.order, e.type()); }
        };
    }

    // S2：第一圈 —— 状态只升不降（版本守卫），积分只去重
    static Handler s2(Stats st) {
        return (c, e) -> {
            if (!firstTime(c, e)) return;
            boolean stale;
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE order_view SET status = ?, last_seq = ? WHERE order_id = ? AND last_seq < ?")) {
                u.setString(1, e.type()); u.setInt(2, e.seq); u.setInt(3, e.order); u.setInt(4, e.seq);
                stale = u.executeUpdate() == 0;
            }
            if (stale && !exists(c, e.order)) {
                try (PreparedStatement i = c.prepareStatement("INSERT INTO order_view VALUES (?, ?, ?)")) {
                    i.setInt(1, e.order); i.setString(2, e.type()); i.setInt(3, e.seq); i.executeUpdate();
                }
                stale = false;
            }
            if (e.seq > 1) {
                addPoints(c, e.order, delta(e));            // 累加型：每条都算，不能丢
                if (!stale) sms(c, e.order, e.type());      // 状态是旧的就不发短信
            }
        };
    }

    static boolean exists(Connection c, int order) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM order_view WHERE order_id = ?")) {
            p.setInt(1, order);
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        }
    }

    static boolean legal(String from, String to) {
        return ("NONE".equals(from) && "CREATED".equals(to))
                || ("CREATED".equals(from) && "PAID".equals(to))
                || ("PAID".equals(from) && "REFUNDED".equals(to));
    }

    // S3 / S4：状态机 + 连续序号 + 跳号暂存 + CAS；lock = 先 SELECT … FOR UPDATE
    static Handler s3(Stats st, boolean lock) {
        return (c, e) -> {
            // 第一次见到这一单：先放一行 NONE / 0（单独提交，供后面加锁和 CAS）
            try (PreparedStatement m = c.prepareStatement(
                    "MERGE INTO order_view USING (VALUES (?)) s(id) ON order_view.order_id = s.id "
                            + "WHEN NOT MATCHED THEN INSERT VALUES (s.id, 'NONE', 0)")) {
                m.setInt(1, e.order); m.executeUpdate();
            }
            c.commit();
            String status; int last;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT status, last_seq FROM order_view WHERE order_id = ?" + (lock ? " FOR UPDATE" : ""))) {
                p.setInt(1, e.order);
                try (ResultSet rs = p.executeQuery()) { rs.next(); status = rs.getString(1); last = rs.getInt(2); }
            }
            if (e.seq <= last) return;                                   // 重复或旧的：确认掉
            if (e.seq > last + 1) {                                      // 跳号：暂存，位点照常提交
                try (PreparedStatement p = c.prepareStatement("MERGE INTO pending KEY(order_id, seq) VALUES (?, ?, ?)")) {
                    p.setInt(1, e.order); p.setInt(2, e.seq); p.setInt(3, e.amt); p.executeUpdate();
                }
                st.parked.incrementAndGet();
                return;
            }
            int oldLast = last;
            Ev cur = e;
            while (cur != null) {
                if (!legal(status, cur.type())) { st.illegal.incrementAndGet(); break; }
                if (cur.seq > 1) {
                    addPoints(c, cur.order, delta(cur));
                    boolean superseded = cur.seq == 2 && pendingHas(c, cur.order, 3);
                    if (superseded) st.skippedStaleSms.incrementAndGet();   // 退款已经在暂存里：不发“付款成功”
                    else sms(c, cur.order, cur.type());
                }
                status = cur.type(); last = cur.seq;
                try (PreparedStatement d = c.prepareStatement("DELETE FROM pending WHERE order_id = ? AND seq = ?")) {
                    d.setInt(1, cur.order); d.setInt(2, cur.seq); d.executeUpdate();
                }
                cur = nextPending(c, cur.order, last + 1);
            }
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE order_view SET status = ?, last_seq = ? WHERE order_id = ? AND last_seq = ?")) {
                u.setString(1, status); u.setInt(2, last); u.setInt(3, e.order); u.setInt(4, oldLast);
                if (u.executeUpdate() == 0) {                            // 别人先改了：整笔回滚，重新来
                    st.casConflicts.incrementAndGet();
                    throw new SQLException("cas", "CAS01");
                }
            }
        };
    }

    static boolean pendingHas(Connection c, int order, int seq) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM pending WHERE order_id = ? AND seq = ?")) {
            p.setInt(1, order); p.setInt(2, seq);
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        }
    }

    static Ev nextPending(Connection c, int order, int seq) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT amt FROM pending WHERE order_id = ? AND seq = ?")) {
            p.setInt(1, order); p.setInt(2, seq);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? new Ev(order, seq, rs.getInt(1)) : null; }
        }
    }

    // ---------------- 运行一种写法 ----------------
    static void schema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE dedup(event_id BIGINT PRIMARY KEY)");
            s.execute("CREATE TABLE order_view(order_id INT PRIMARY KEY, status VARCHAR(10), last_seq INT)");
            s.execute("CREATE TABLE order_points(order_id INT PRIMARY KEY, points INT NOT NULL CHECK (points >= 0))");
            s.execute("CREATE TABLE sms(id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id INT, kind VARCHAR(10))");
            s.execute("CREATE TABLE pending(order_id INT, seq INT, amt INT, PRIMARY KEY(order_id, seq))");
        }
    }

    /** 一条事件一笔事务；主键冲突、CAS 失败、锁超时就重来；CHECK 失败（先扣后加）重试 3 次后放弃 */
    static void process(Connection c, Handler h, Ev e, Stats st) throws InterruptedException {
        int checkFails = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                h.handle(c, e);
                c.commit();
                return;
            } catch (SQLException ex) {
                try { c.rollback(); } catch (SQLException ignore) { }
                if (isCheck(ex)) {
                    if (++checkFails >= 3) { st.givenUp.incrementAndGet(); return; }   // 进死信，不再管
                    Thread.sleep(2);
                }
            }
        }
        st.givenUp.incrementAndGet();
    }

    static void run(String name, String db, java.util.function.Function<Stats, Handler> make, List<Ev> stream,
                    boolean repair) throws Exception {
        Stats st = new Stats();
        Handler h = make.apply(st);
        String url = "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";
        try (Connection admin = DriverManager.getConnection(url)) {
            schema(admin);
            ConcurrentLinkedQueue<Ev> q = new ConcurrentLinkedQueue<>(stream);
            long t0 = System.nanoTime();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < 2; w++) {
                fs.add(pool.submit(() -> {
                    try (Connection c = DriverManager.getConnection(url)) {
                        c.setAutoCommit(false);
                        Ev e;
                        while ((e = q.poll()) != null) process(c, h, e, st);
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            pool.shutdown();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            // 暂存里还剩的单：分成“丢了事件”和“竞态卡住”
            Set<Integer> stuck = new TreeSet<>();
            try (ResultSet rs = admin.createStatement().executeQuery("SELECT DISTINCT order_id FROM pending")) {
                while (rs.next()) stuck.add(rs.getInt(1));
            }
            int stuckLost = 0;
            for (int o : stuck) if (LOST.contains(o)) stuckLost++;
            String repairNote = "";
            if (repair && !stuck.isEmpty()) {
                // 回查：向订单服务要这一单 last_seq 之后的事件，按同一套逻辑再处理一遍
                admin.setAutoCommit(false);
                for (int o : stuck) for (Ev e : TRUTH.get(o)) process(admin, h, e, st);
                admin.setAutoCommit(true);
                int left = count(admin, "SELECT COUNT(DISTINCT order_id) FROM pending");
                int orphan = count(admin, "SELECT COUNT(DISTINCT p.order_id) FROM pending p JOIN order_view v "
                        + "ON p.order_id = v.order_id WHERE p.seq <= v.last_seq");
                repairNote = "；回查后还剩 " + left + " 单（其中序号早已处理过的孤儿 " + orphan + " 单）";
            }
            // 和真相对比
            int wrongStatus = 0, wrongPoints = 0, smsOutOfOrder = 0, paidSmsOnRefunded = 0;
            for (Map.Entry<Integer, List<Ev>> en : TRUTH.entrySet()) {
                int o = en.getKey();
                List<Ev> t = en.getValue();
                String want = t.get(t.size() - 1).type();
                int wantPts = t.size() == 3 ? 0 : t.get(1).amt;
                String got = str(admin, "SELECT status FROM order_view WHERE order_id = " + o);
                Integer pts = intOrNull(admin, "SELECT points FROM order_points WHERE order_id = " + o);
                if (!want.equals(got)) wrongStatus++;
                if (pts == null ? wantPts != 0 : pts != wantPts) wrongPoints++;
                List<String> kinds = new ArrayList<>();
                try (ResultSet rs = admin.createStatement().executeQuery(
                        "SELECT kind FROM sms WHERE order_id = " + o + " ORDER BY id")) {
                    while (rs.next()) kinds.add(rs.getString(1));
                }
                int iPaid = kinds.indexOf("PAID"), iRef = kinds.indexOf("REFUNDED");
                if (iPaid >= 0 && iRef >= 0 && iPaid > iRef) smsOutOfOrder++;
                if (t.size() == 3 && iPaid >= 0) paidSmsOnRefunded++;
            }
            System.out.printf("[%s] 状态错 %d 单，积分错 %d 单，先扣后加被放弃 %d 次，短信先退款后付款 %d 单，"
                            + "已退款单收到付款短信 %d 单（其中按暂存跳过了 %d 条）%n",
                    name, wrongStatus, wrongPoints, st.givenUp.get(), smsOutOfOrder, paidSmsOnRefunded,
                    st.skippedStaleSms.get());
            if (make != null && (st.parked.get() > 0 || st.casConflicts.get() > 0)) {
                System.out.printf("    暂存 %d 次，CAS 冲突回滚 %d 次，非法转换 %d 次；处理完时暂存里卡着 %d 单（丢了付款事件 %d + 竞态 %d）%s%n",
                        st.parked.get(), st.casConflicts.get(), st.illegal.get(), stuck.size(), stuckLost,
                        stuck.size() - stuckLost, repairNote);
            }
            System.out.printf("    耗时 %d ms%n", ms);
        }
    }

    static int count(Connection c, String sql) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) { rs.next(); return rs.getInt(1); }
    }
    static String str(Connection c, String sql) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) { return rs.next() ? rs.getString(1) : null; }
    }
    static Integer intOrNull(Connection c, String sql) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) { return rs.next() ? rs.getInt(1) : null; }
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
        List<Ev> stream = buildStream(seed);
        int refunded = 0;
        for (List<Ev> t : TRUTH.values()) if (t.size() == 3) refunded++;
        System.out.printf("事件流：%d 单（其中退款 %d 单），共 %d 条；单内乱序 %d 单，重复 %d 条，重平衡重投 %d 条，丢了付款事件 %d 单；两个线程并发处理%n",
                ORDERS, refunded, stream.size(), reordered, dups, redelivered, LOST.size());
        run("S1 去重表 + 直接覆盖", "s1", ReorderChaos::s1, stream, false);
        run("S2 第一圈：版本守卫 + 积分只去重", "s2", ReorderChaos::s2, stream, false);
        run("S3 状态机 + 连续序号 + 暂存，只靠 CAS", "s3", st -> s3(st, false), stream, true);
        run("S4 S3 + 先锁这一单的行", "s4", st -> s3(st, true), stream, true);
    }
}
