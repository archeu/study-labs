package labs.network.tcp_handshake_timewait_framing;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * TIME_WAIT 留在谁那边。
 *
 * 订单服务每调一次库存就新建一条连接（短连接），调 N 次后用 ss 数 TIME_WAIT：
 *   client  模式：客户端先 close（主动关闭）
 *   server  模式：服务端回完就 close
 *   linger0 模式：客户端 SO_LINGER=0 再 close，发 RST，不进 TIME_WAIT
 *   leak    模式：客户端先关，服务端读到 -1 却忘了 close —— 服务端堆 CLOSE_WAIT，客户端停在 FIN_WAIT_2
 * 只在 Linux 上能跑（要用 ss 命令）。
 *
 * 运行：mvn -q -pl network/tcp-handshake-timewait-framing compile exec:java \
 *        -Dexec.mainClass=labs.network.tcp_handshake_timewait_framing.TimeWait -Dexec.args="client 500"
 */
public class TimeWait {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "client";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 500;

        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            List<Socket> leaked = java.util.Collections.synchronizedList(new ArrayList<>());
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket s = server.accept();
                        try {
                            InputStream in = s.getInputStream();
                            in.read();                       // 收到 1 字节请求
                            s.getOutputStream().write('k');  // 回 1 字节
                            if (!mode.equals("server")) {
                                while (in.read() != -1) { }  // 等客户端先关（读到 -1 = 对面发了 FIN）
                            }
                        } catch (java.io.IOException reset) {
                            // linger0 模式：对面发的是 RST，read 抛 Connection reset
                        } finally {
                            if (mode.equals("leak")) leaked.add(s);  // 忘了 close：对面的 FIN 收到了，自己不回 FIN
                            else s.close();                  // server 模式下，这就是服务端先关
                        }
                    }
                } catch (Exception ignored) { }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Socket c = new Socket(server.getInetAddress(), port);
                c.getOutputStream().write('r');
                InputStream in = c.getInputStream();
                in.read();
                if (mode.equals("server")) {
                    while (in.read() != -1) { }          // 等服务端的 FIN
                }
                if (mode.equals("linger0")) c.setSoLinger(true, 0);
                c.close();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Thread.sleep(200);
            System.out.printf("模式 %s：%d 次短连接，用时 %d ms，服务端口 %d%n", mode, n, ms, port);
            report(port);
        }
    }

    /** 用 ss 列出和这个服务端口有关的连接，按状态、按在哪一边数。 */
    static void report(int port) throws Exception {
        Process p = new ProcessBuilder("ss", "-tanoH").redirectErrorStream(true).start();
        java.util.Map<String, int[]> count = new java.util.TreeMap<>();
        String sample = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 5 || f[0].equals("LISTEN")) continue;
                String state = f[0], local = f[3], peer = f[4];
                int side;
                if (peer.endsWith(":" + port)) side = 0;        // 本地是客户端
                else if (local.endsWith(":" + port)) side = 1;  // 本地是服务端
                else continue;
                count.computeIfAbsent(state, k -> new int[2])[side]++;
                if (sample == null && !state.equals("ESTAB")) sample = line.trim().replaceAll("\\s+", " ");
            }
        }
        p.waitFor();
        for (java.util.Map.Entry<String, int[]> e : count.entrySet()) {
            System.out.printf("   %-10s 客户端（订单服务）%4d 条   服务端（库存服务）%4d 条%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        if (count.isEmpty()) System.out.println("   和这个端口有关的连接一条都没留下");
        if (sample != null) System.out.println("   其中一条: " + sample);
    }
}
