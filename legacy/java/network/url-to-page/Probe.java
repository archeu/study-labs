import javax.net.ssl.*;
public class Probe {
    public static void main(String[] a) throws Exception {
        SSLContext c = SSLContext.getDefault();
        System.out.println(System.getProperty("java.version") + " default client protocols: " + String.join(",", c.getDefaultSSLParameters().getProtocols()));
        try {
            Object v = Class.forName("sun.net.InetAddressCachePolicy").getMethod("get").invoke(null);
            Object n = Class.forName("sun.net.InetAddressCachePolicy").getMethod("getNegative").invoke(null);
            System.out.println("  JVM DNS cache ttl=" + v + "s negative=" + n + "s");
        } catch (Throwable t) { System.out.println("  reflect: " + t); }
    }
}
