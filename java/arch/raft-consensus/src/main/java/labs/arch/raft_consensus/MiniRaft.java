package labs.arch.raft_consensus;

import java.util.*;

/**
 * 一个 300 行的 Raft：只有选主和日志复制，没有快照、没有成员变更、没有持久化到磁盘。
 * 三个节点 tc-1、tc-2、tc-3 对应 J09 全景里 Seata TC 的 3 节点集群。
 *
 * 不起线程、不开端口：所有消息和定时器都放进一个按“虚拟毫秒”排序的事件队列，
 * 单线程一个个执行，所以每次跑出来的时间线一模一样，方便对照页面上的分步图。
 *
 * 时间参数取 SOFAJRaft（Seata TC 用的 Raft 库）的量级：
 * 选举超时 1000 ms + 随机 0~1000 ms，leader 每 100 ms 发一次心跳，网络单程 5 ms。
 *
 * 用法：java ... MiniRaft all | elect | replicate | partition | restrict | split | rejoin
 */
public class MiniRaft {

    static final int ELECTION_BASE = 1000, ELECTION_JITTER = 1000, HEARTBEAT = 100, LATENCY = 5;
    static final int LEASE = ELECTION_BASE * 9 / 10;   // 和 JRaft 一样：leader 租约 = 选举超时的 90%

    enum Role { FOLLOWER, CANDIDATE, LEADER }

    static final class Entry {
        final int term; final String cmd;
        Entry(int term, String cmd) { this.term = term; this.cmd = cmd; }
        public String toString() { return "t" + term + ":" + cmd; }
    }

    // ---------- 消息：Raft 只有两种 RPC（外加 PreVote） ----------
    static abstract class Msg { String from, to; int term; }
    static final class VoteReq extends Msg { int lastIndex, lastTerm; boolean pre; }
    static final class VoteResp extends Msg { boolean granted, pre; }
    static final class AppendReq extends Msg { int prevIndex, prevTerm, leaderCommit; List<Entry> entries; }
    static final class AppendResp extends Msg { boolean ok; int matchIndex; }

    // ---------- 虚拟时钟 + 事件队列 ----------
    static final class Event implements Comparable<Event> {
        final long at, seq; final Runnable r;
        Event(long at, long seq, Runnable r) { this.at = at; this.seq = seq; this.r = r; }
        public int compareTo(Event o) { return at != o.at ? Long.compare(at, o.at) : Long.compare(seq, o.seq); }
    }
    final PriorityQueue<Event> queue = new PriorityQueue<Event>();
    long now = 0, seq = 0;
    void at(long t, Runnable r) { queue.add(new Event(t, seq++, r)); }
    void runUntil(long t) {
        while (!queue.isEmpty() && queue.peek().at <= t) { Event e = queue.poll(); now = e.at; e.r.run(); }
        now = t;
    }

    final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
    final Set<String> cut = new HashSet<String>();          // 断开的链路 "a>b"
    final Map<String, Integer> fixedTimeout = new HashMap<String, Integer>();
    final Random rnd;
    boolean randomized = true, preVote = false, quiet = false;
    int leaderChanges = 0;

    MiniRaft(long seed) { rnd = new Random(seed); for (String id : new String[]{"tc-1", "tc-2", "tc-3"}) nodes.put(id, new Node(id)); }

    int majority() { return nodes.size() / 2 + 1; }
    void log(String who, String s) { if (!quiet) System.out.printf("  t=%5d ms  %-5s %s%n", now, who, s); }
    void note(String s) { if (!quiet) System.out.printf("  t=%5d ms  ----- %s%n", now, s); }

    void isolate(String id) { for (String o : nodes.keySet()) if (!o.equals(id)) { cut.add(id + ">" + o); cut.add(o + ">" + id); } }
    void heal() { cut.clear(); }
    void send(final Msg m) {
        if (cut.contains(m.from + ">" + m.to)) return;                 // 断网：消息直接丢
        at(now + LATENCY, new Runnable() { public void run() {
            if (cut.contains(m.from + ">" + m.to)) return;
            nodes.get(m.to).receive(m);
        }});
    }
    void start() { for (Node n : nodes.values()) n.resetElectionTimer(); }
    Node leader() { Node l = null; for (Node n : nodes.values()) if (!n.down && n.role == Role.LEADER && (l == null || n.term > l.term)) l = n; return l; }

    final class Node {
        final String id;
        Role role = Role.FOLLOWER;
        int term = 0; String votedFor = null;                // 这两个和 log 要落盘（这里省了）
        final List<Entry> log = new ArrayList<Entry>();    // index 从 1 开始：log.get(i - 1)
        int commitIndex = 0;
        long timerToken = 0, lastLeaderContact = Long.MIN_VALUE / 2; int lastTimeout;
        boolean down = false;
        final Set<String> votes = new HashSet<String>();
        final Map<String, Integer> nextIndex = new HashMap<String, Integer>(), matchIndex = new HashMap<String, Integer>();
        final Map<String, Long> lastAck = new HashMap<String, Long>();

        Node(String id) { this.id = id; }
        int lastIndex() { return log.size(); }
        int termAt(int i) { return i == 0 ? 0 : log.get(i - 1).term; }
        List<String> peers() { List<String> p = new ArrayList<String>(nodes.keySet()); p.remove(id); return p; }

        // ---------- 选主 ----------
        void resetElectionTimer() {
            final long token = ++timerToken;
            lastTimeout = fixedTimeout.containsKey(id) ? fixedTimeout.get(id)
                    : ELECTION_BASE + (randomized ? rnd.nextInt(ELECTION_JITTER) : 0);
            at(now + lastTimeout, new Runnable() { public void run() {
                if (token == timerToken && !down && role != Role.LEADER) electionTimeout();
            }});
        }

        void electionTimeout() {
            if (preVote) {                                   // 先“摸底”，不涨任期
                votes.clear(); votes.add(id);
                log(id, "等不到心跳，先预投票（PreVote，假装任期 " + (term + 1) + "，自己的任期不动）");
                for (String p : peers()) send(voteReq(p, term + 1, true));
                resetElectionTimer();
                return;
            }
            startElection();
        }

        void startElection() {
            role = Role.CANDIDATE; term++; votedFor = id; votes.clear(); votes.add(id);
            log(id, "等了 " + lastTimeout + " ms 没心跳 → 任期 " + term + "，投自己，向其他节点要票（我最后一条 index="
                    + lastIndex() + " term=" + termAt(lastIndex()) + "）");
            for (String p : peers()) send(voteReq(p, term, false));
            resetElectionTimer();
        }

        VoteReq voteReq(String to, int t, boolean pre) {
            VoteReq r = new VoteReq(); r.from = id; r.to = to; r.term = t; r.pre = pre;
            r.lastIndex = lastIndex(); r.lastTerm = termAt(lastIndex()); return r;
        }

        void receive(Msg m) {
            if (down) return;
            boolean isPreReq = m instanceof VoteReq && ((VoteReq) m).pre;
            if (!isPreReq && m.term > term) {               // 规则 0：看到更大的任期，马上认输
                if (role != Role.FOLLOWER) log(id, "看到更大的任期 " + m.term + "（来自 " + m.from + "），从 " + role + " 退回 FOLLOWER");
                boolean wasLeader = role == Role.LEADER;
                term = m.term; votedFor = null; role = Role.FOLLOWER;
                if (wasLeader) resetElectionTimer();
            }
            if (m instanceof VoteReq) onVoteReq((VoteReq) m);
            else if (m instanceof VoteResp) onVoteResp((VoteResp) m);
            else if (m instanceof AppendReq) onAppendReq((AppendReq) m);
            else onAppendResp((AppendResp) m);
        }

        void onVoteReq(VoteReq r) {
            int myLastTerm = termAt(lastIndex());
            boolean upToDate = r.lastTerm > myLastTerm || (r.lastTerm == myLastTerm && r.lastIndex >= lastIndex());
            boolean grant; String why;
            if (r.pre) {
                boolean leaderAlive = role == Role.LEADER || now - lastLeaderContact < ELECTION_BASE;
                grant = r.term > term && !leaderAlive && upToDate;
                why = leaderAlive ? "（leader 还活着，不配合）" : !upToDate ? "（它的日志不如我新）" : "";
            } else {
                boolean freeVote = votedFor == null || votedFor.equals(r.from);
                grant = r.term == term && freeVote && upToDate;
                why = r.term < term ? "（它的任期 " + r.term + " 比我的 " + term + " 旧）"
                    : !freeVote ? "（任期 " + term + " 我已经投给 " + votedFor + "）"
                    : !upToDate ? "（它最后一条 term=" + r.lastTerm + " index=" + r.lastIndex + "，不如我的 term=" + myLastTerm + " index=" + lastIndex() + " 新）" : "";
                if (grant) { votedFor = r.from; resetElectionTimer(); }
            }
            log(id, (r.pre ? "预投票：" : "") + (grant ? "投给 " : "不投 ") + r.from + why);
            VoteResp resp = new VoteResp(); resp.from = id; resp.to = r.from; resp.term = term; resp.granted = grant; resp.pre = r.pre;
            send(resp);
        }

        void onVoteResp(VoteResp r) {
            if (!r.granted) return;
            if (r.pre) {
                if (role == Role.LEADER || votes.contains(r.from)) return;
                votes.add(r.from);
                if (votes.size() >= majority()) { log(id, "预投票过半，开始真选举"); startElection(); }
                return;
            }
            if (role != Role.CANDIDATE || r.term != term) return;
            votes.add(r.from);
            if (votes.size() >= majority()) becomeLeader();
        }

        void becomeLeader() {
            role = Role.LEADER; leaderChanges++;
            log(id, "拿到 " + votes.size() + "/" + nodes.size() + " 票 → 任期 " + term + " 的 leader");
            for (String p : peers()) { nextIndex.put(p, lastIndex() + 1); matchIndex.put(p, 0); lastAck.put(p, now); }
            log.add(new Entry(term, "no-op"));               // 新官上任先写一条空记录，好把旧任期的记录一起提交
            final int myTerm = term;
            heartbeat(myTerm);
        }

        void heartbeat(final int myTerm) {
            if (down || role != Role.LEADER || term != myTerm) return;
            int reachable = 1;
            for (String p : peers()) if (now - lastAck.get(p) <= LEASE) reachable++;
            if (reachable < majority()) {                    // 联系不上多数派：主动退位（check quorum）
                log(id, "已经 " + LEASE + " ms 联系不上多数派 → 自己退位");
                role = Role.FOLLOWER; resetElectionTimer(); return;
            }
            for (String p : peers()) replicateTo(p);
            at(now + HEARTBEAT, new Runnable() { public void run() { heartbeat(myTerm); } });
        }

        // ---------- 日志复制 ----------
        int propose(String cmd) {
            if (role != Role.LEADER) { log(id, "收到 " + cmd + "，但我不是 leader，拒绝"); return -1; }
            log.add(new Entry(term, cmd));
            log(id, "追加 index " + lastIndex() + "（" + log.get(lastIndex() - 1) + "），发给 follower，等过半");
            for (String p : peers()) replicateTo(p);
            return lastIndex();
        }

        void replicateTo(String p) {
            int prev = nextIndex.get(p) - 1;
            AppendReq r = new AppendReq(); r.from = id; r.to = p; r.term = term;
            r.prevIndex = prev; r.prevTerm = termAt(prev); r.leaderCommit = commitIndex;
            r.entries = new ArrayList<Entry>(log.subList(prev, log.size()));
            send(r);
        }

        void onAppendReq(AppendReq r) {
            AppendResp resp = new AppendResp(); resp.from = id; resp.to = r.from;
            if (r.term < term) { resp.term = term; resp.ok = false; send(resp); return; }   // 旧 leader 的消息
            if (role != Role.FOLLOWER) log(id, "收到任期 " + r.term + " 的 leader " + r.from + " 的心跳，退回 FOLLOWER");
            role = Role.FOLLOWER; lastLeaderContact = now; resetElectionTimer();
            resp.term = term;
            if (r.prevIndex > lastIndex() || termAt(r.prevIndex) != r.prevTerm) {          // 一致性检查
                log(id, "对不上：leader 说 index " + r.prevIndex + " 是 term " + r.prevTerm + "，我这里"
                        + (r.prevIndex > lastIndex() ? "还没有这一条" : "是 term " + termAt(r.prevIndex)) + " → 拒绝，让它往前退一格");
                resp.ok = false; send(resp); return;
            }
            int i = r.prevIndex;
            for (Entry e : r.entries) {
                i++;
                if (i <= lastIndex() && termAt(i) != e.term) {                            // 冲突：删掉自己从这里往后的
                    List<Entry> tail = new ArrayList<Entry>(log.subList(i - 1, log.size()));
                    log.subList(i - 1, log.size()).clear();
                    log(id, "index " + i + " 起和 leader 冲突，删掉 " + tail);
                }
                if (i > lastIndex()) { log.add(e); log(id, "追加 index " + i + "（" + e + "）"); }
            }
            int newCommit = Math.min(r.leaderCommit, r.prevIndex + r.entries.size());
            if (newCommit > commitIndex) commitIndex = newCommit;
            resp.ok = true; resp.matchIndex = r.prevIndex + r.entries.size(); send(resp);
        }

        void onAppendResp(AppendResp r) {
            if (role != Role.LEADER || r.term != term) return;
            lastAck.put(r.from, now);
            if (!r.ok) { nextIndex.put(r.from, Math.max(1, nextIndex.get(r.from) - 1)); replicateTo(r.from); return; }
            if (r.matchIndex > matchIndex.get(r.from)) matchIndex.put(r.from, r.matchIndex);
            nextIndex.put(r.from, matchIndex.get(r.from) + 1);
            for (int n = lastIndex(); n > commitIndex; n--) {
                if (termAt(n) != term) break;                 // 只按“本任期的记录”数票（论文 5.4.2）
                int cnt = 1;
                for (int m : matchIndex.values()) if (m >= n) cnt++;
                if (cnt >= majority()) {
                    for (int k = commitIndex + 1; k <= n; k++)
                        log(id, "index " + k + "（" + log.get(k - 1) + "）已在 " + cnt + "/" + nodes.size() + " 个节点上 → 提交");
                    commitIndex = n; break;
                }
            }
        }

        String show() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= lastIndex(); i++) sb.append(i <= commitIndex ? " " : " *").append(i).append(':').append(log.get(i - 1));
            return String.format("  %-5s %-9s term=%d commit=%d  [%s ]", id, down ? "DOWN" : role, term, commitIndex, sb.toString().trim());
        }
    }

    void printLogs(String title) {
        if (quiet) return;
        System.out.println("  ── " + title + "（* = 还没提交）");
        for (Node n : nodes.values()) System.out.println(n.show());
    }

    // ---------- 场景 ----------
    /** 让 tc-1 当选任期 1 的 leader，再提交两条全局事务记录，和全景第 3、7 站对上。 */
    static MiniRaft bootstrap(long seed, boolean print) {
        MiniRaft c = new MiniRaft(seed);
        c.quiet = !print;
        c.fixedTimeout.put("tc-1", 1100); c.fixedTimeout.put("tc-2", 1500); c.fixedTimeout.put("tc-3", 1800);
        c.start(); c.runUntil(1200);
        c.fixedTimeout.clear();
        c.nodes.get("tc-1").propose("BEGIN 9527"); c.runUntil(1250);
        c.nodes.get("tc-1").propose("COMMITTING 9527"); c.runUntil(1300);
        c.quiet = false;
        return c;
    }

    static void elect() {
        System.out.println("\n[elect] 3 个节点同时启动，谁先等不到心跳谁先参选（随机超时 1000~2000 ms，seed=7）");
        MiniRaft c = new MiniRaft(7);
        c.start();
        for (Node n : c.nodes.values()) System.out.printf("  %s 这一轮的选举超时：%d ms%n", n.id, n.lastTimeout);
        c.runUntil(2200);
        Node l = c.leader();
        System.out.println("  结果：" + l.id + " 是任期 " + l.term + " 的 leader，之后每 " + HEARTBEAT + " ms 发心跳，其他节点的计时器一直被重置");
    }

    static void replicate() {
        System.out.println("\n[replicate] tc-1 当选后，全景第 3 站和第 7 站各写一条记录");
        bootstrap(1, true);
        System.out.println("  结果：每条记录都是“写进 leader + 至少 1 个 follower（2/3）”才提交，不用等第三个");
    }

    static void partition() {
        System.out.println("\n[partition] 第 7 站之后 tc-1 断网：少数派那边写不进，多数派那边选新 leader，回来后旧记录被覆盖");
        MiniRaft c = bootstrap(3, false);
        c.printLogs("断网前");
        c.note("tc-1 断网");
        c.isolate("tc-1");
        c.nodes.get("tc-1").propose("BEGIN 9528");          // 订单服务还连着 tc-1
        c.runUntil(c.now + 3000);
        Node nl = c.leader();
        nl.propose("BEGIN 9529");
        c.runUntil(c.now + 50);
        c.printLogs("断网 3 秒后");
        c.note("网络恢复");
        c.heal();
        c.runUntil(c.now + 300);
        c.printLogs("恢复 300 ms 后");
        System.out.println("  结果：tc-1 那条 BEGIN 9528 从没过半、从没提交，被新 leader 的日志覆盖；订单服务那次请求早已超时失败，要重试");
    }

    static void restrict() {
        System.out.println("\n[restrict] tc-3 宕机期间错过了两条已提交的记录，tc-1 一挂，tc-3 先醒——它能当 leader 吗？");
        MiniRaft c = bootstrap(1, false);
        c.nodes.get("tc-3").down = true;
        c.note("tc-3 宕机");
        c.nodes.get("tc-1").propose("BEGIN 9528"); c.runUntil(c.now + 30);
        c.nodes.get("tc-1").propose("COMMITTING 9528"); c.runUntil(c.now + 30);
        c.printLogs("tc-3 宕机期间");
        c.note("tc-1 宕机，tc-3 重启");
        c.nodes.get("tc-1").down = true;
        Node t3 = c.nodes.get("tc-3"); t3.down = false; t3.role = Role.FOLLOWER;
        c.fixedTimeout.put("tc-3", 1050); c.fixedTimeout.put("tc-2", 1600);
        t3.resetElectionTimer(); c.nodes.get("tc-2").resetElectionTimer();
        c.runUntil(c.now + 1800);
        c.printLogs("1.8 秒后");
        System.out.println("  结果：tc-3 先参选但拿不到 tc-2 的票（日志旧）；tc-2 当选后把 9528 的两条补给 tc-3。已提交的记录一条没丢");
    }

    static void split() {
        System.out.println("\n[split] 把随机去掉：3 个节点的超时都是 1000 ms");
        MiniRaft c = new MiniRaft(1);
        c.randomized = false; c.start(); c.runUntil(1500);
        c.quiet = true; c.runUntil(5500);
        System.out.println("  ……第 2～5 轮（2000、3000、4000、5000 ms）一模一样，省略");
        Node l = c.leader();
        System.out.println("  结果：5.5 秒、" + c.nodes.get("tc-1").term + " 轮选举，" + (l == null ? "一个 leader 都没选出来（每轮都是 1:1:1）" : l.id + " 当选"));
        int[] firstRound = new int[1]; int ok = 0, total = 1000;
        for (int s = 0; s < total; s++) {
            MiniRaft r = new MiniRaft(s); r.quiet = true; r.start(); r.runUntil(2100);
            Node rl = r.leader(); if (rl != null && rl.term == 1) ok++;
        }
        System.out.println("  对照：恢复随机超时（1000~2000 ms），换 " + total + " 个随机种子各跑一次，第 1 轮就选出 leader 的有 " + ok + " 次");
    }

    static void rejoin() {
        System.out.println("\n[rejoin] tc-3 断网 5 秒再回来：它一个人在那边不停参选，任期涨上去了");
        for (boolean pv : new boolean[]{false, true}) {
            MiniRaft c = bootstrap(2, false);
            c.preVote = pv; c.quiet = true;
            c.isolate("tc-3"); c.runUntil(c.now + 5000);
            int t3 = c.nodes.get("tc-3").term;
            c.heal();
            int before = c.leaderChanges; long healAt = c.now; long noLeader = 0;
            for (long t = healAt; t < healAt + 4000; t += 10) { c.runUntil(t); if (c.leader() == null) noLeader += 10; }
            Node l = c.leader();
            System.out.printf("  %-8s 断网期间 tc-3 的任期涨到 %d；回来后 leader 换了 %d 次，没有 leader 的时间约 %d ms，最后 leader=%s 任期=%d%n",
                    pv ? "开 PreVote" : "关 PreVote", t3, c.leaderChanges - before, noLeader, l == null ? "无" : l.id, l == null ? -1 : l.term);
        }
    }

    public static void main(String[] args) {
        String what = args.length > 0 ? args[0] : "all";
        if (what.equals("all") || what.equals("elect")) elect();
        if (what.equals("all") || what.equals("replicate")) replicate();
        if (what.equals("all") || what.equals("partition")) partition();
        if (what.equals("all") || what.equals("restrict")) restrict();
        if (what.equals("all") || what.equals("split")) split();
        if (what.equals("all") || what.equals("rejoin")) rejoin();
    }
}
