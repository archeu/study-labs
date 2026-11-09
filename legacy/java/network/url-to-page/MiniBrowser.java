import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** 不用任何 HTTP 库，把浏览器联网那半段自己走一遍。JDK 8 起都能编译运行。 */
public class MiniBrowser {
    public static void main(String[] args) throws Exception {
        URI url = URI.create(args.length > 0 ? args[0] : "https://lwn.net/");
        String tlsVer = args.length > 1 ? args[1] : null;                 // 可选：TLSv1.2 / TLSv1.3
        SSLSocketFactory factory = SSLContext.getDefault().getSocketFactory(); // 加载信任库，不计时
        for (int round = 1; round <= 2; round++) fetch(url, tlsVer, factory, round);
    }

    static void fetch(URI url, String tlsVer, SSLSocketFactory factory, int round) throws Exception {
        String host = url.getHost();
        int port = url.getPort() == -1 ? 443 : url.getPort();
        String path = url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        long[] us = new long[5];                                          // 每一段的耗时，单位微秒
        long t = System.nanoTime();

        InetAddress ip = InetAddress.getByName(host);                     // ① DNS：名字 → IP
        us[0] = lap(t); t = System.nanoTime();

        Socket tcp = new Socket();
        tcp.setTcpNoDelay(true);                                          // 关掉 Nagle，浏览器也是这样（⑥ 坑 6）
        tcp.connect(new InetSocketAddress(ip, port), 5000);               // ② TCP 三次握手
        us[1] = lap(t); t = System.nanoTime();

        SSLSocket s = (SSLSocket) factory.createSocket(tcp, host, port, true); // ③ TLS 握手，SNI = host
        if (tlsVer != null) s.setEnabledProtocols(new String[]{tlsVer});
        s.startHandshake();
        us[2] = lap(t); t = System.nanoTime();

        OutputStream out = s.getOutputStream();                           // ④ HTTP 请求就是几行文本
        out.write(("GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: MiniBrowser/1.0\r\n"
                + "Accept: text/html\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = s.getInputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(in.read());                                            // 阻塞到第一个字节回来
        us[3] = lap(t); t = System.nanoTime();

        byte[] buf = new byte[16384];                                     // ⑤ 读到服务器关连接为止
        for (int n; (n = in.read(buf)) != -1; ) body.write(buf, 0, n);
        us[4] = lap(t);
        String proto = s.getSession().getProtocol();
        s.close();

        String[] name = {"DNS", "TCP", "TLS", "首字节", "下载完"};
        StringBuilder line = new StringBuilder("第 " + round + " 次 ");
        long sum = 0;
        for (int i = 0; i < 5; i++) {
            sum += us[i];
            line.append(String.format(" %s %.1f", name[i], us[i] / 1000.0));
            if (i > 0) line.append(String.format("(%.1f RTT)", (double) us[i] / us[1]));
        }
        System.out.println(line + String.format("  合计 %.1f ms", sum / 1000.0));
        if (round == 2) report(host, ip, proto, body.toString("UTF-8"));
    }

    static long lap(long start) { return (System.nanoTime() - start) / 1000; }

    static void report(String host, InetAddress ip, String proto, String resp) {
        int split = resp.indexOf("\r\n\r\n");
        String[] head = resp.substring(0, split).split("\r\n");
        String html = resp.substring(split + 4);
        System.out.println("\n" + host + " → " + ip.getHostAddress() + " · " + proto);
        System.out.println(head[0] + " · 响应 " + resp.length() + " 字节");
        for (String h : head)
            if (h.matches("(?i)(content-type|cache-control|content-encoding|transfer-encoding):.*"))
                System.out.println("  " + h);
        System.out.println("HTML 里还引用了（浏览器接着要去下载）：");          // ⑥ 读 HTML，发现子资源
        Matcher m = Pattern.compile("<(link|script|img)\\b[^>]*?\\b(?:href|src)=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) System.out.println("  <" + m.group(1) + "> " + m.group(2));
    }
}
