package labs.arch.raft_consensus;

import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个真正的 SOFAJRaft 节点（Seata TC 的 raft 模式用的就是它），由 {@link JRaftLab} 以子进程方式拉起。
 * 当上 leader 后每 20 ms 提交一条 "BEGIN n"，打印每条从 apply 到“过半提交、状态机执行完”的耗时。
 * 标准输出只打 "EV ..." 开头的事件行，给父进程解析。
 *
 * 参数：<id: tc-1|tc-2|tc-3> <数据目录>
 */
public class JRaftNode {
    static final String GROUP = "seata-tc";
    static final String CONF = "127.0.0.1:18081,127.0.0.1:18082,127.0.0.1:18083";

    static void ev(String s) { synchronized (System.out) { System.out.println("EV " + System.currentTimeMillis() + " " + s); System.out.flush(); } }

    public static void main(String[] args) throws Exception {
        final String id = args[0];
        int port = 18080 + Integer.parseInt(id.substring(3));
        File dir = new File(args[1], id);
        dir.mkdirs();

        final AtomicLong leaderTerm = new AtomicLong(-1);
        final AtomicLong applied = new AtomicLong();
        NodeOptions opts = new NodeOptions();
        opts.setElectionTimeoutMs(1000);                                  // = seata.server.raft.election-timeout-ms 默认值
        opts.setInitialConf(conf());
        opts.setLogUri(new File(dir, "log").getPath());
        opts.setRaftMetaUri(new File(dir, "meta").getPath());
        opts.setFsm(new StateMachineAdapter() {
            @Override public void onApply(Iterator it) {
                while (it.hasNext()) {                                      // 已经过半提交的记录，按顺序交给状态机
                    applied.incrementAndGet();
                    if (it.done() != null) it.done().run(Status.OK());
                    it.next();
                }
            }
            @Override public void onLeaderStart(long term) { leaderTerm.set(term); ev("LEADER " + id + " " + term); }
            @Override public void onLeaderStop(Status status) { leaderTerm.set(-1); ev("STEPDOWN " + id + " " + status.getErrorMsg()); }
        });

        final RaftGroupService svc = new RaftGroupService(GROUP, new PeerId("127.0.0.1", port), opts);
        final Node node = svc.start();
        ev("UP " + id);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() { public void run() {
            ev("SHUTDOWN " + id);
            svc.shutdown();                                                // 优雅停机：leader 会先让出位置
            try { svc.join(); } catch (InterruptedException ignored) { }
        }}));

        long seq = 0;
        while (true) {
            final long term = leaderTerm.get();
            if (term < 0) { Thread.sleep(5); continue; }
            final long n = ++seq;
            final long t0 = System.nanoTime();
            Task task = new Task();
            task.setData(ByteBuffer.wrap(("BEGIN " + n).getBytes(StandardCharsets.UTF_8)));
            task.setDone(st -> {
                long us = (System.nanoTime() - t0) / 1000;
                if (st.isOk()) ev("COMMIT " + id + " " + term + " " + n + " " + us);
                else ev("FAIL " + id + " " + term + " " + n + " " + st.getErrorMsg().replace(' ', '_'));
            });
            task.setExpectedTerm(term);
            node.apply(task);
            Thread.sleep(20);
        }
    }

    static Configuration conf() { Configuration c = new Configuration(); c.parse(CONF); return c; }
}
