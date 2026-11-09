import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 积分服务变慢，会不会拖垮整个下单系统？JDK 8 / 17 / 21 都能跑。 */
public class Avalanche {
    static final int RPS = 200, SECONDS = 5;          // 每秒 200 个请求，压 5 秒
    static final int CLIENT_GIVE_UP_MS = 1000;        // 用户（网关）最多等 1 秒
    static final int WORKERS = 20, QUEUE = 50;        // 缩小版 Tomcat：20 个工作线程，50 个排队位
    static final int POINTS_TIMEOUT_MS = 200;         // 调积分服务的超时
    static final int BULKHEAD = 5;                    // 积分调用最多同时占 5 个线程（舱壁）

    enum Mode { NAKED, TIMEOUT, GUARDED }
    static int pointsDelayMs;                         // 积分服务这次要多久才回
    static Semaphore bulkhead;

    /** 下单里要调的积分服务；三种写法 */
    static void callPoints(Mode m) throws Exception {
        if (m == Mode.NAKED) { Thread.sleep(pointsDelayMs); return; }      // 没超时：它多慢我就等多久
        if (m == Mode.GUARDED && !bulkhead.tryAcquire()) throw new RejectedExecutionException("舱壁满");
        try {
            if (pointsDelayMs > POINTS_TIMEOUT_MS) { Thread.sleep(POINTS_TIMEOUT_MS); throw new TimeoutException(); }
            Thread.sleep(pointsDelayMs);
        } finally { if (m == Mode.GUARDED) bulkhead.release(); }
    }

    static final class Req { boolean order; long arrive, done; String result; }

    static void handle(Req r, Mode m) {
        try {
            if (r.order) {
                Thread.sleep(10);                                           // 下单自己的活：10 ms
                try { callPoints(m); r.result = "ok"; }
                catch (Exception e) { r.result = (m == Mode.GUARDED) ? "degraded" : "error"; } // 降级：积分记下来稍后补
            } else { Thread.sleep(5); r.result = "ok"; }                     // 查商品：5 ms，不碰积分
        } catch (InterruptedException e) { r.result = "error"; }
        r.done = System.nanoTime();
    }

    static void run(String title, Mode m, int delay) throws Exception {
        pointsDelayMs = delay; bulkhead = new Semaphore(BULKHEAD);
        ThreadPoolExecutor tomcat = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE));
        List<Req> all = new ArrayList<Req>();
        int peak = 0;
        long start = System.nanoTime();
        for (int i = 0; i < RPS * SECONDS; i++) {
            long due = start + i * 1_000_000_000L / RPS;
            while (System.nanoTime() < due) Thread.sleep(0, 200_000);
            final Req r = new Req(); r.order = i % 10 < 3; r.arrive = System.nanoTime(); all.add(r);
            try { tomcat.execute(() -> handle(r, m)); } catch (RejectedExecutionException e) { r.result = "rejected"; }
            peak = Math.max(peak, tomcat.getActiveCount());
        }
        tomcat.shutdown(); tomcat.awaitTermination(60, TimeUnit.SECONDS);
        int[] q = new int[5], o = new int[5];   // 0 成功 1 降级 2 出错 3 超过1秒 4 被拒
        List<Long> qms = new ArrayList<Long>();
        for (Req r : all) {
            int k;
            if ("rejected".equals(r.result)) k = 4;
            else {
                long ms = (r.done - r.arrive) / 1_000_000;
                if (!r.order) qms.add(ms);
                k = ms > CLIENT_GIVE_UP_MS ? 3 : "ok".equals(r.result) ? 0 : "degraded".equals(r.result) ? 1 : 2;
            }
            (r.order ? o : q)[k]++;
        }
        Collections.sort(qms);
        String p99 = qms.isEmpty() ? "-" : qms.get((int) (qms.size() * 0.99)) + " ms";
        int nq = RPS * SECONDS * 7 / 10, no = RPS * SECONDS * 3 / 10;
        System.out.printf("%-16s 查询 成功 %3d%%  p99 %-8s | 下单 成功 %3d%% 降级 %3d%% | 超1秒 %3d  被拒 %3d | 线程峰值 %d/%d%n",
                title, q[0] * 100 / nq, p99, o[0] * 100 / no, o[1] * 100 / no, q[3] + o[3], q[4] + o[4], peak, WORKERS);
    }

    public static void main(String[] a) throws Exception {
        System.out.println("Java " + System.getProperty("java.version") + "，" + RPS + " 请求/秒 × " + SECONDS + " 秒，下单 30% / 查询 70%");
        run("正常 · 裸奔", Mode.NAKED, 20);
        run("积分卡 3 秒 · 裸奔", Mode.NAKED, 3000);
        run("积分卡 3 秒 · 只加超时", Mode.TIMEOUT, 3000);
        run("积分卡 3 秒 · 超时+隔离+降级", Mode.GUARDED, 3000);
    }
}
