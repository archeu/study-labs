package labs.juc.thread_state_context_switch;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** 读 Linux 的 /proc：Java 线程就是内核线程，内核那边也能看到它。只在 Linux 上有效。 */
final class Proc {
    private Proc() {}

    /** 线程名（内核只留前 15 个字符） → 内核线程号 tid。 */
    static Map<String, String> tidsByName() throws IOException {
        Map<String, String> m = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) {
            for (Path p : ds) m.put(read(p.resolve("comm")).trim(), p.getFileName().toString());
        }
        return m;
    }

    static int taskCount() throws IOException {
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("/proc/self/task"))) {
            for (Path ignored : ds) n++;
        }
        return n;
    }

    /** 内核眼里的状态：R 在跑或排队等 CPU，S 睡着（可被唤醒），D 不可中断的睡眠。 */
    static String kernelState(String tid) throws IOException {
        String stat = read(Paths.get("/proc/self/task", tid, "stat"));
        return stat.substring(stat.lastIndexOf(')') + 2, stat.lastIndexOf(')') + 3);
    }

    /** 当前线程自己被切换了多少次：{主动让出, 被抢占}。 */
    static long[] myCtxSwitches() throws IOException {
        long vol = 0, nonvol = 0;
        for (String line : read(Paths.get("/proc/thread-self/status")).split("\n")) {
            if (line.startsWith("voluntary_ctxt_switches")) vol = Long.parseLong(line.split("\\s+")[1]);
            if (line.startsWith("nonvoluntary_ctxt_switches")) nonvol = Long.parseLong(line.split("\\s+")[1]);
        }
        return new long[]{vol, nonvol};
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), "UTF-8");
    }
}
