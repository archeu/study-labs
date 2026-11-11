package labs.network.tcp_handshake_timewait_framing;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 粘包、拆包，以及长度前缀怎么把“消息”找回来。
 *
 * 场景：订单服务连着库存服务，连续发 5 条扣库存请求。
 *  1) 粘包：客户端 write 了 5 次，服务端一次 read 就全拿到了。
 *  2) 拆包：客户端一次 write 200 KB，服务端要 read 好几次。
 *  3) 长度前缀：每条消息前面加 4 字节长度，接收方 readInt + readFully，一条不多一条不少。
 *
 * 运行：mvn -q -pl network/tcp-handshake-timewait-framing compile exec:java \
 *        -Dexec.mainClass=labs.network.tcp_handshake_timewait_framing.Framing
 */
public class Framing {

    static final String[] MSGS = {
            "reserve order=9527 sku=88 qty=2",
            "reserve order=9528 sku=12 qty=1",
            "reserve order=9529 sku=88 qty=5",
            "reserve order=9530 sku=07 qty=1",
            "reserve order=9531 sku=88 qty=3",
    };

    public static void main(String[] args) throws Exception {
        sticky();
        split();
        lengthPrefixed();
    }

    /** 1) 5 次 write，服务端晚 100 ms 才 read：字节都在接收缓冲区里排着，一次全读出来。 */
    static void sticky() throws Exception {
        System.out.println("== 1) 粘包：客户端 write 5 次，每次一条 ==");
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    Thread.sleep(100); // 库存服务的线程忙了一下
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[8192];
                    int n, reads = 0;
                    while ((n = in.read(buf)) != -1) {
                        reads++;
                        System.out.printf("   服务端第 %d 次 read 拿到 %d 字节: %s%n", reads, n,
                                new String(buf, 0, n, StandardCharsets.UTF_8).replace("\n", "⏎"));
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            t.start();
            try (Socket c = new Socket(server.getInetAddress(), server.getLocalPort())) {
                OutputStream out = c.getOutputStream();
                for (String m : MSGS) out.write((m + "\n").getBytes(StandardCharsets.UTF_8));
            }
            t.join();
        }
    }

    /** 2) 一次 write 200 KB，接收方的 read 每次只拿到缓冲区里当时有的那一段。 */
    static void split() throws Exception {
        System.out.println("== 2) 拆包：客户端 write 1 次，200 KB ==");
        final int size = 200 * 1024;
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n, reads = 0, total = 0;
                    StringBuilder sizes = new StringBuilder();
                    while ((n = in.read(buf)) != -1) {
                        reads++;
                        total += n;
                        if (sizes.length() > 0) sizes.append(" + ");
                        sizes.append(n);
                    }
                    System.out.printf("   服务端 read 了 %d 次: %s = %d 字节%n", reads, sizes, total);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            t.start();
            try (Socket c = new Socket(server.getInetAddress(), server.getLocalPort())) {
                c.getOutputStream().write(new byte[size]);
            }
            t.join();
        }
    }

    /** 3) 4 字节长度 + 内容。发送方照样连着写，接收方按长度切。 */
    static void lengthPrefixed() throws Exception {
        System.out.println("== 3) 长度前缀：每条前面加 4 字节长度 ==");
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    Thread.sleep(100); // 同样晚读，字节同样粘在一起
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    int count = 0;
                    while (true) {
                        int len;
                        try { len = in.readInt(); } catch (java.io.EOFException eof) { break; }
                        if (len < 0 || len > 1 << 20) throw new IllegalStateException("帧太大: " + len);
                        byte[] body = new byte[len];
                        in.readFully(body); // 不够就接着读，直到凑满 len 字节
                        count++;
                        System.out.printf("   第 %d 条 (%d 字节): %s%n", count, len,
                                new String(body, StandardCharsets.UTF_8));
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            t.start();
            try (Socket c = new Socket(server.getInetAddress(), server.getLocalPort())) {
                DataOutputStream out = new DataOutputStream(c.getOutputStream());
                for (String m : MSGS) {
                    byte[] b = m.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(b.length);
                    out.write(b);
                }
                out.flush();
            }
            t.join();
        }
    }
}
