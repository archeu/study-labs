package labs.arch.cap_base_consistency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 交易靶场的券服务：MINUS20 这类券的状态存在 3 个副本上（N=3）。
 * 每张券有两单来抢（同一个用户两台设备，或两张单），抢完马上读一次“我锁到了吗”。
 * 网络每 100 个时间片里有一段被分区：随机一个节点被隔开，自己一边。
 * 写入：读 R 个能联系上的副本拿最新版本，是 UNUSED 才写，写进 W 个副本才算成功，其余副本异步复制（延迟 1..lag 片）。
 * 统计：拒绝了多少（不可用）、同一张券被两单都锁到（要补偿）、刚锁到又读到 UNUSED（读到旧值）。
 *
 * 和页面实验台的 JS 是同一套逻辑、同一个随机数（mulberry32），同样的参数输出同样的数字。
 */
public class CouponQuorum {

    static final int N = 3;
    static final int PERIOD = 100;

    static final class Rng {
        private int a;
        Rng(int seed) { this.a = seed; }
        double next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
        }
    }

    static final class Ev {
        final int t, s, k, ord, c; final boolean read;
        Ev(int t, int s, boolean read, int k, int ord, int c) { this.t = t; this.s = s; this.read = read; this.k = k; this.ord = ord; this.c = c; }
    }

    static final class Msg {
        final int to, from, k, owner, ver, due;
        Msg(int to, int from, int k, int owner, int ver, int due) { this.to = to; this.from = from; this.k = k; this.owner = owner; this.ver = ver; this.due = due; }
    }

    static final class Result {
        int lockReq, lockRejected, lockOk, alreadyUsed, doubleLock, reads, readRejected, staleReads, divergeAfterHeal;
    }

    final int w, r, coupons, ticks, partLen, lagMax;
    final Rng rnd;
    final int[] iso;
    final int[][] owner, ver;
    List<Msg> q = new ArrayList<Msg>();

    CouponQuorum(int w, int r, double part, int lag, int seed) {
        this.w = w; this.r = r; this.coupons = 1000; this.ticks = 10000;
        this.partLen = (int) Math.round(part * PERIOD); this.lagMax = lag;
        this.rnd = new Rng(seed);
        iso = new int[ticks / PERIOD + 2];
        for (int p = 0; p < iso.length; p++) iso[p] = (int) Math.floor(rnd.next() * N);
        owner = new int[N][coupons];
        ver = new int[N][coupons];
    }

    /** 从节点 c 在时刻 t 能联系上的节点，c 自己排第一个 */
    List<Integer> reach(int c, int t) {
        int p = t / PERIOD; boolean inPart = (t % PERIOD) < partLen;
        List<Integer> res = new ArrayList<Integer>(); res.add(c);
        for (int i = 0; i < N; i++) {
            if (i == c) continue;
            if (inPart && (c == iso[p] || i == iso[p])) continue;
            res.add(i);
        }
        return res;
    }

    void apply(int n, int k, int ow, int v) { if (v > ver[n][k]) { ver[n][k] = v; owner[n][k] = ow; } }

    void deliver(int t) {
        List<Msg> keep = new ArrayList<Msg>();
        for (Msg x : q) {
            if (x.due <= t && reach(x.from, t).contains(x.to)) apply(x.to, x.k, x.owner, x.ver); else keep.add(x);
        }
        q = keep;
    }

    /** 读一组副本，取版本最大的那个值 */
    int readQuorum(List<Integer> nodes, int k) {
        int b = nodes.get(0);
        for (int i = 1; i < nodes.size(); i++) if (ver[nodes.get(i)][k] > ver[b][k]) b = nodes.get(i);
        return owner[b][k];
    }

    Result run() {
        List<Ev> ev = new ArrayList<Ev>(); int seq = 0;
        for (int k = 0; k < coupons; k++) {
            int tA = (int) Math.floor((double) k * ticks / coupons) + (int) Math.floor(rnd.next() * 5);
            int gap = 2 + (int) Math.floor(rnd.next() * 20);
            ev.add(new Ev(tA, seq++, false, k, 1, (int) Math.floor(rnd.next() * N)));
            ev.add(new Ev(tA + 1, seq++, true, k, 0, (int) Math.floor(rnd.next() * N)));
            ev.add(new Ev(tA + gap, seq++, false, k, 2, (int) Math.floor(rnd.next() * N)));
        }
        Collections.sort(ev, (a, b) -> a.t != b.t ? Integer.compare(a.t, b.t) : Integer.compare(a.s, b.s));

        Result m = new Result(); int gv = 0; int[] got = new int[coupons]; int cur = -1;
        for (Ev e : ev) {
            if (e.t != cur) { cur = e.t; deliver(cur); }
            List<Integer> rc = reach(e.c, e.t); int k = e.k;
            if (e.read) {
                m.reads++;
                if (rc.size() < r) { m.readRejected++; continue; }
                int ow = readQuorum(rc.subList(0, r), k);
                if ((got[k] & 1) != 0 && ow != 2 * k + 1) m.staleReads++;
                continue;
            }
            m.lockReq++;
            if (rc.size() < r || rc.size() < w) { m.lockRejected++; continue; }   // 凑不够 R 或 W 个副本：拒绝
            if (readQuorum(rc.subList(0, r), k) != 0) { m.alreadyUsed++; continue; }  // 读到已被锁：正常的“券已用”
            int v = ++gv, ow = 2 * k + e.ord;
            List<Integer> wset = rc.subList(0, w);
            for (int n : wset) apply(n, k, ow, v);
            for (int n = 0; n < N; n++) {
                if (!wset.contains(n)) q.add(new Msg(n, e.c, k, ow, v, e.t + (lagMax > 0 ? 1 + (int) Math.floor(rnd.next() * lagMax) : 0)));
            }
            m.lockOk++; got[k] |= (e.ord == 1 ? 1 : 2);
        }
        for (int k = 0; k < coupons; k++) if (got[k] == 3) m.doubleLock++;
        for (Msg x : q) apply(x.to, x.k, x.owner, x.ver);          // 分区恢复：反熵，版本大的赢（LWW）
        for (int k = 0; k < coupons; k++) if (!(owner[0][k] == owner[1][k] && owner[1][k] == owner[2][k])) m.divergeAfterHeal++;
        return m;
    }

    public static void main(String[] args) {
        double part = args.length > 0 ? Double.parseDouble(args[0]) : 0.1;
        int lag = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int seed = args.length > 2 ? Integer.parseInt(args[2]) : 42;
        System.out.printf("3 副本 · 1000 张券 · 每张两单来抢 · 分区时间占比 %.0f%% · 异步复制延迟 %s · 种子 %d%n",
                part * 100, lag > 0 ? "1.." + lag + " 片" : "无", seed);
        System.out.println("W R | W+R>N | 抢券被拒(不可用) | 两单都锁到(要补偿) | 刚锁到读成UNUSED | 读被拒 | 恢复后副本不一致");
        int[][] cfg = {{1, 1}, {2, 1}, {2, 2}, {3, 1}, {1, 3}};
        for (int[] c : cfg) {
            Result m = new CouponQuorum(c[0], c[1], part, lag, seed).run();
            System.out.printf("%d %d | %-5s | %4d / %d | %4d | %4d | %4d | %d%n",
                    c[0], c[1], c[0] + c[1] > N ? "是" : "否", m.lockRejected, m.lockReq, m.doubleLock, m.staleReads, m.readRejected, m.divergeAfterHeal);
        }
    }
}
