import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 用一个线程打开 N 个连接，各发一次 ping；然后测：有人发 slow 时，其他 100 个连接的 ping 要等多久。 */
public class IoClient {
    public static void main(String[] a) throws Exception {
        int n = Integer.parseInt(a[0]), port = Integer.parseInt(a[1]);
        Selector sel = Selector.open();
        SocketChannel[] cs = new SocketChannel[n];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            cs[i] = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            cs[i].configureBlocking(false);
            cs[i].register(sel, SelectionKey.OP_READ, i);
        }
        long tConn = System.nanoTime();
        for (SocketChannel c : cs) send(c, "ping\n");
        waitPongs(sel, n, null);
        long tPong = System.nanoTime();
        System.out.printf("打开 %d 个连接 %d ms，%d 个 ping 全部回来又用了 %d ms%n",
                n, (tConn - t0) / 1_000_000, n, (tPong - tConn) / 1_000_000);

        Thread.sleep(5000);                      // 全部空闲，让服务端打印线程数和内存

        // 连接 0 发一个要处理 200 ms 的请求，紧接着 100 个别的连接各发 ping
        long[] start = new long[n]; long[] lat = new long[n];
        send(cs[0], "slow\n");
        Thread.sleep(5);
        for (int i = 1; i <= 100; i++) { start[i] = System.nanoTime(); send(cs[i], "ping\n"); }
        waitPongs(sel, 101, (i, now) -> lat[i] = (now - start[i]) / 1_000_000);
        long[] l = Arrays.copyOfRange(lat, 1, 101); Arrays.sort(l);
        System.out.printf("有人发 slow 时，其他 100 个 ping：中位数 %d ms，最慢 %d ms%n", l[50], l[99]);
        for (SocketChannel c : cs) c.close();
    }
    interface OnPong { void at(int i, long now); }
    static void send(SocketChannel c, String s) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
        while (b.hasRemaining()) c.write(b);
    }
    static void waitPongs(Selector sel, int expect, OnPong cb) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(64); int got = 0;
        while (got < expect) {
            sel.select();
            Iterator<SelectionKey> it = sel.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey k = it.next(); it.remove();
                buf.clear(); int r = ((SocketChannel) k.channel()).read(buf);
                for (int j = 0; j < r; j++) if (buf.get(j) == '\n') {
                    got++; if (cb != null) cb.at((Integer) k.attachment(), System.nanoTime());
                }
            }
        }
    }
}
