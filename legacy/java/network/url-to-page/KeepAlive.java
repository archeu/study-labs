import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** 练习 1 的参考解：同一条 TLS 连接上连发两次 GET，按 chunked / Content-Length 判断响应结束。 */
public class KeepAlive {
    public static void main(String[] args) throws Exception {
        String host = "lwn.net";
        SSLSocketFactory f = SSLContext.getDefault().getSocketFactory();
        for (int warm = 0; warm < 2; warm++) {
            long t = System.nanoTime();
            Socket tcp = new Socket(); tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(InetAddress.getByName(host), 443), 5000);
            SSLSocket s = (SSLSocket) f.createSocket(tcp, host, 443, true);
            s.startHandshake();
            double hs = (System.nanoTime() - t) / 1e6;
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            for (int i = 1; i <= 2; i++) {
                t = System.nanoTime();
                out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: MiniBrowser/1.0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                int len = readResponse(in);
                if (warm == 1) System.out.printf("请求 %d: %s%.1f ms，body %d 字节%n", i, i == 1 ? String.format("握手 %.1f ms + 请求 ", hs) : "只有请求 ", (System.nanoTime() - t) / 1e6, len);
            }
            s.close();
        }
    }
    static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) if (c != '\r') b.append((char) c);
        return b.toString();
    }
    static int readResponse(InputStream in) throws IOException {
        int contentLength = -1; boolean chunked = false;
        for (String h; !(h = line(in)).isEmpty(); ) {
            String k = h.toLowerCase();
            if (k.startsWith("content-length:")) contentLength = Integer.parseInt(h.substring(15).trim());
            if (k.startsWith("transfer-encoding:") && k.contains("chunked")) chunked = true;
        }
        int total = 0;
        if (chunked) {
            for (int n; (n = Integer.parseInt(line(in).split(";")[0].trim(), 16)) > 0; total += n) { skip(in, n); line(in); }
            while (!line(in).isEmpty()) { }          // 末尾的空行（以及可能的 trailer）
        } else { skip(in, contentLength); total = contentLength; }
        return total;
    }
    static void skip(InputStream in, int n) throws IOException { while (n > 0) { if (in.read() == -1) throw new EOFException(); n--; } }
}
