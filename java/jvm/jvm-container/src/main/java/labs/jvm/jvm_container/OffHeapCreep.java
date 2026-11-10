package labs.jvm.jvm_container;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 堆里先放 200 MB 活对象，然后每 200 ms 申请 16 MB 直接内存（模拟 Netty 缓冲越积越多），
 * 每步打印进程实际占用的物理内存 VmRSS。在 512 MB 的 cgroup 里：
 *   不限直接内存 → 进程被内核杀掉，退出码 137，没有任何 Java 异常；
 *   -XX:MaxDirectMemorySize=64m → JVM 自己抛 OutOfMemoryError: Direct buffer memory，能被日志记下来。
 */
public class OffHeapCreep {
    public static void main(String[] args) throws Exception {
        List<byte[]> heap = new ArrayList<byte[]>();
        for (int i = 0; i < 200; i++) {
            heap.add(new byte[1024 * 1024]);            // 堆里的活数据，每块都写过，真占物理内存
            heap.get(i)[0] = 1;
        }
        System.out.printf("heap ballast 200 MB, -Xmx=%d MB, RSS=%s%n",
                Runtime.getRuntime().maxMemory() >> 20, rss());
        List<ByteBuffer> direct = new ArrayList<ByteBuffer>();
        try {
            for (int i = 1; ; i++) {
                direct.add(ByteBuffer.allocateDirect(16 * 1024 * 1024)); // allocateDirect 会清零，页被真正写到
                System.out.printf("direct %4d MB, RSS=%s%n", i * 16, rss());
                Thread.sleep(200);
            }
        } catch (OutOfMemoryError e) {
            System.out.println("JVM 自己报错了：" + e);
            System.exit(3);
        }
    }

    static String rss() throws Exception {
        for (String l : Files.readAllLines(Paths.get("/proc/self/status"))) {
            if (l.startsWith("VmRSS")) return l.substring(6).trim();
        }
        return "?";
    }
}
