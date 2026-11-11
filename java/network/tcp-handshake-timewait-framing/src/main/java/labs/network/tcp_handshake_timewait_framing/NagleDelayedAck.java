package labs.network.tcp_handshake_timewait_framing;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

/**
 * 40 ms 是怎么来的：Nagle 算法 × 延迟 ACK。
 *
 * 客户端把一次请求分两次 write：先写 5 字节前缀，再写 7 字节消息（和 gRPC 的格式一样长），然后等 1 字节回复。
 *  - 不开 TCP_NODELAY：第二次 write 是小包，前一个小包的 ACK 还没回来，Nagle 让它等；
 *    服务端收到前 5 字节，凑不成一条完整请求，没有回复可捎带 ACK，延迟 ACK 也等着。两边互等。
 *  - 开 TCP_NODELAY：两次 write 立刻发出，一个往返就完。
 *
 * 运行：mvn -q -pl network/tcp-handshake-timewait-framing compile exec:java \
 *        -Dexec.mainClass=labs.network.tcp_handshake_timewait_framing.NagleDelayedAck
 */
public class NagleDelayedAck {

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        run(false, rounds);
        run(true, rounds);
    }

    static void run(boolean noDelay, int rounds) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    byte[] req = new byte[12];
                    while (true) {
                        in.readFully(req);   // 凑满 12 字节才算一条完整请求
                        out.write('k');
                    }
                } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();

            try (Socket c = new Socket(server.getInetAddress(), server.getLocalPort())) {
                c.setTcpNoDelay(noDelay);
                OutputStream out = c.getOutputStream();
                InputStream in = c.getInputStream();
                byte[] prefix = {0, 0, 0, 0, 7};
                byte[] body = {8, (byte) 0xb7, 0x4a, 0x10, 0x58, 0x18, 0x02};
                double[] ms = new double[rounds];
                for (int i = 0; i < rounds; i++) {
                    long t0 = System.nanoTime();
                    out.write(prefix);   // 第一次 write
                    out.write(body);     // 第二次 write
                    in.read();           // 等回复
                    ms[i] = (System.nanoTime() - t0) / 1e6;
                }
                // 前一半当预热，只统计后一半
                double[] tail = Arrays.copyOfRange(ms, rounds / 2, rounds);
                Arrays.sort(tail);
                System.out.printf("TCP_NODELAY=%-5s  %d 次调用（统计后 %d 次）  p50 %.2f ms  p99 %.2f ms  前 5 次: %s%n",
                        noDelay, rounds, tail.length, tail[tail.length / 2], tail[(int) (tail.length * 0.99)],
                        fmt(Arrays.copyOf(ms, 5)));
            }
        }
    }

    static String fmt(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (double d : a) sb.append(String.format("%.1f ", d));
        return sb.toString().trim();
    }
}
