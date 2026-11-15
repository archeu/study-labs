package labs.arch.distributed_id;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段发号器：一次从 id_alloc 领一段 [max_id - step + 1, max_id]，放在内存里一个个发。
 * doubleBuffer = true 时，当前段用掉 10% 就在后台线程预取下一段（和美团 Leaf 的 SegmentIDGenImpl 同一个思路），
 * 当前段用完直接切过去，调用方不等库。
 */
public class SegmentIdGen {

    /** 一段号：[start, max]，next 是下一个要发的号 */
    static final class Segment {
        final long start, max;
        final AtomicLong next;
        Segment(long start, long max) { this.start = start; this.max = max; this.next = new AtomicLong(start); }
        long idle() { return max - next.get() + 1; }
        @Override public String toString() { return "[" + start + ", " + max + "]"; }
    }

    final String name;
    private final String tag;
    private final boolean doubleBuffer;
    private final Connection conn;
    private final ExecutorService loader;
    private final AtomicBoolean loading = new AtomicBoolean();

    private volatile Segment cur;
    private volatile Segment nxt;

    /** 访问 id_alloc 的次数（含失败的） */
    final AtomicInteger dbCalls = new AtomicInteger();
    /** 模拟：每次领号段时库要多花多少毫秒（在同一个事务里 DO SLEEP） */
    volatile int dbDelayMs;
    /** 模拟：库连不上 */
    volatile boolean dbDown;

    public SegmentIdGen(String name, String url, String tag, boolean doubleBuffer) throws SQLException {
        this.name = name;
        this.tag = tag;
        this.doubleBuffer = doubleBuffer;
        this.conn = DriverManager.getConnection(url, "root", "root");
        this.conn.setAutoCommit(false);
        this.loader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name + "-segment-loader");
            t.setDaemon(true);
            return t;
        });
        this.cur = fetch();                       // 启动时领第一段
    }

    Segment current() { return cur; }

    /** 预取好、还没用上的下一段；没有就是 null */
    String nxtString() { Segment n = nxt; return n == null ? null : n.toString(); }

    /** UPDATE + SELECT 放在一个事务里：行锁保证两个实例领到的段不重叠 */
    private Segment fetch() throws SQLException {
        synchronized (conn) { return fetch0(); }   // 锁连接，不锁 this：切段时持着 this 等预取，预取线程不能再要 this
    }

    private Segment fetch0() throws SQLException {
        dbCalls.incrementAndGet();
        if (dbDown) throw new SQLException("id_alloc 所在的库连不上（模拟）");
        try {
            if (dbDelayMs > 0) {
                try (Statement s = conn.createStatement()) { s.execute("DO SLEEP(" + dbDelayMs / 1000.0 + ")"); }
            }
            try (PreparedStatement up = conn.prepareStatement(
                    "UPDATE id_alloc SET max_id = max_id + step WHERE biz_tag = ?")) {
                up.setString(1, tag);
                if (up.executeUpdate() != 1) throw new SQLException("biz_tag 不存在：" + tag);
            }
            long max, step;
            try (PreparedStatement q = conn.prepareStatement("SELECT max_id, step FROM id_alloc WHERE biz_tag = ?")) {
                q.setString(1, tag);
                try (ResultSet rs = q.executeQuery()) { rs.next(); max = rs.getLong(1); step = rs.getLong(2); }
            }
            conn.commit();
            return new Segment(max - step + 1, max);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    public long nextId() throws SQLException {
        while (true) {
            Segment s = cur;
            long step = s.max - s.start + 1;
            if (doubleBuffer && nxt == null && s.idle() < 0.9 * step && loading.compareAndSet(false, true)) {
                loader.execute(() -> {
                    Segment got = null;
                    try { got = fetch(); } catch (SQLException e) { if (!dbDown) System.err.println(name + " 预取失败：" + e); }
                    // “下一段到了”和“预取结束”必须一起生效（Leaf 用写锁包住 setNextReady + threadRunning=false）：
                    // 分开写的话，预取线程在两句之间被抢占，切段线程会看到 nxt 已被用掉、loading 却还是 true，
                    // 新的预取发不出去，这一段发完时就误报“两段都空”
                    synchronized (this) { if (got != null) nxt = got; loading.set(false); notifyAll(); }
                });
            }
            long v = s.next.getAndIncrement();
            if (v <= s.max) return v;
            synchronized (this) {                  // 当前段发完了：只让一个线程去切换
                if (cur != s) continue;            // 别的线程已经切过了
                if (!doubleBuffer) {
                    cur = fetch();                 // 单 buffer：调用方在这里等库
                    continue;
                }
                while (cur == s && nxt == null && loading.get()) {      // 预取还在路上：等它（wait 会让出 this）
                    try { wait(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                if (cur != s) continue;            // 等的时候，另一个也在等的线程已经切过了
                if (nxt == null) throw new IllegalStateException(name + "：两段都发完了，库还没给新号段");
                cur = nxt;
                nxt = null;
            }
        }
    }

    public void close() {
        loader.shutdownNow();
        try { conn.close(); } catch (SQLException ignore) { }
    }
}
