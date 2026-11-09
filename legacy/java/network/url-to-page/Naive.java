import java.io.*;
import java.net.Socket;
public class Naive {
    public static void main(String[] a) throws Exception {
        try (Socket s = new Socket("lwn.net", 80)) {                 // 这一行里藏着 DNS 和 TCP 握手
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: lwn.net\r\nConnection: close\r\n\r\n".getBytes());
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            for (String line; (line = r.readLine()) != null; ) System.out.println(line);
        }
    }
}
