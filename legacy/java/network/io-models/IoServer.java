import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** 一个回声服务，三种写法：bio（一连接一线程）、nio（一个线程 + Selector）、vt（虚拟线程，JDK 21）。
 *  收到 "ping" 回 "pong"；收到 "slow" 先睡 200 ms 再回（模拟一次阻塞调用）。 */
public class IoServer {
    static final AtomicInteger conns = new AtomicInteger();

    public static void main(String[] a) throws Exception {
        String mode = a[0]; int port = Integer.parseInt(a[1]);
        startStats(mode);
        if (mode.equals("nio")) nio(port); else blocking(mode, port);
    }

    // ---- bio / vt：阻塞写法，每个连接一个线程 ----
    static void blocking(String mode, int port) throws Exception {
        ExecutorService vt = null;
        if (mode.equals("vt"))  // 反射调用，好让 --release 8 也能编译；只有 JDK 21+ 能跑这个模式
            vt = (ExecutorService) java.util.concurrent.Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        ServerSocket ss = new ServerSocket(port, 4096);
        while (true) {
            final Socket s = ss.accept();
            conns.incrementAndGet();
            Runnable r = () -> serve(s);
            if (vt != null) vt.execute(r); else new Thread(r).start();
        }
    }
    static void serve(Socket s) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = s.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null) {          // 没数据时，线程就停在这里等
                if (line.equals("slow")) Thread.sleep(200);
                out.write("pong\n".getBytes(StandardCharsets.US_ASCII));
            }
        } catch (Exception ignored) { }
        conns.decrementAndGet();
    }

    // ---- nio：一个线程用 Selector 管所有连接 ----
    static void nio(int port) throws Exception {
        Selector sel = Selector.open();
        ServerSocketChannel ss = ServerSocketChannel.open();
        ss.bind(new InetSocketAddress(port), 4096);
        ss.configureBlocking(false);
        ss.register(sel, SelectionKey.OP_ACCEPT);
        ByteBuffer buf = ByteBuffer.allocate(64);
        ByteBuffer pong = ByteBuffer.wrap("pong\n".getBytes(StandardCharsets.US_ASCII));
        while (true) {
            sel.select();                                      // 睡到有连接“有事”为止
            Iterator<SelectionKey> it = sel.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey k = it.next(); it.remove();
                if (k.isAcceptable()) {
                    SocketChannel c = ss.accept();
                    c.configureBlocking(false);
                    c.register(sel, SelectionKey.OP_READ);
                    conns.incrementAndGet();
                } else if (k.isReadable()) {
                    SocketChannel c = (SocketChannel) k.channel();
                    buf.clear();
                    int n = c.read(buf);
                    if (n < 0) { k.cancel(); c.close(); conns.decrementAndGet(); continue; }
                    // 演示用：假设一行在一次 read 里读完（真实代码要处理半包，见 ⑥）
                    String msg = new String(buf.array(), 0, n, StandardCharsets.US_ASCII);
                    for (String line : msg.split("\n")) {
                        if (line.equals("slow")) Thread.sleep(200);   // 在事件循环里阻塞：所有连接一起等
                        pong.rewind(); c.write(pong);
                    }
                }
            }
        }
    }

    // ---- 每 2 秒打印：连接数、线程数、RSS ----
    static void startStats(String mode) {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                System.gc();
                long heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
                System.out.printf("[%s] 连接 %d  JVM 线程 %d  RSS %d MB  其中堆已用 %d MB%n", mode, conns.get(),
                        ManagementFactory.getThreadMXBean().getThreadCount(), rssMb(), heap);
            }
        });
        t.setDaemon(true); t.start();
    }
    static long rssMb() {
        try {
            for (String l : Files.readAllLines(Paths.get("/proc/self/status")))
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D", "")) / 1024;
        } catch (IOException ignored) { }
        return -1;
    }
}
