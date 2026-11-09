package labs.javase.io_nio_basics;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * 交易靶场里的 IO：导出对账 CSV、读商品导入文件、字符集、ByteBuffer 的指针。
 * java -cp target/classes labs.javase.io_nio_basics.Demo <工作目录>
 */
public class Demo {
    record OrderRow(long id, long userId, String sku, BigDecimal total) {}

    public static void main(String[] args) throws IOException {
        Path dir = Files.createDirectories(Path.of(args.length > 0 ? args[0] : "io-demo"));
        System.out.println(System.getProperty("java.version") + "，默认字符集 " + Charset.defaultCharset());

        System.out.println("\n== 1. 导出对账 CSV：字符流 + 缓冲 + 明确写 UTF-8 ==");
        Path csv = dir.resolve("orders-20261109.csv");
        List<OrderRow> rows = List.of(new OrderRow(9527, 1024, "机械键盘", new BigDecimal("578.00")),
                new OrderRow(9528, 2048, "显示器支架", new BigDecimal("199.00")));
        try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            w.write("订单号,用户,商品,应付"); w.newLine();
            for (OrderRow r : rows) { w.write(r.id() + "," + r.userId() + "," + r.sku() + "," + r.total()); w.newLine(); }
        }
        System.out.println("  写了 " + Files.size(csv) + " 字节，" + Files.readAllLines(csv).size() + " 行");

        System.out.println("\n== 2. 同一份字节，用错字符集解码 ==");
        byte[] bytes = Files.readAllBytes(csv);
        String firstLine = new String(bytes, StandardCharsets.UTF_8).lines().skip(1).findFirst().orElseThrow();
        System.out.println("  UTF-8 读：" + firstLine);
        System.out.println("  GBK   读：" + new String(bytes, Charset.forName("GBK")).lines().skip(1).findFirst().orElseThrow());
        System.out.println("  ISO-8859-1 读：" + new String(bytes, StandardCharsets.ISO_8859_1).lines().skip(1).findFirst().orElseThrow());
        byte[] kb = "机械键盘".getBytes(StandardCharsets.UTF_8);
        System.out.println("  “机械键盘” UTF-8 是 " + kb.length + " 字节；只取前 4 个字节解码：" + new String(kb, 0, 4, StandardCharsets.UTF_8));

        System.out.println("\n== 3. 读商品导入文件：Files.lines 是惰性的，要关 ==");
        Path imp = dir.resolve("products.csv");
        try (BufferedWriter w = Files.newBufferedWriter(imp)) {
            for (int i = 1; i <= 100_000; i++) { w.write(i + ",商品" + i + "," + (10 + i % 990)); w.newLine(); }
        }
        try (Stream<String> lines = Files.lines(imp)) {           // 不 close，文件句柄就一直开着
            long expensive = lines.map(l -> l.split(",")).filter(f -> Integer.parseInt(f[2]) > 900).count();
            System.out.println("  " + Files.size(imp) / 1024 + " KB、10 万行，单价 > 900 的有 " + expensive + " 个");
        }

        System.out.println("\n== 4. ByteBuffer 的三个指针：写模式、flip、读模式 ==");
        ByteBuffer buf = ByteBuffer.allocate(16);
        show("allocate(16)", buf);
        buf.put("9527,".getBytes(StandardCharsets.US_ASCII));
        show("put 5 字节", buf);
        buf.flip();
        show("flip()", buf);
        byte[] four = new byte[4]; buf.get(four);
        show("get 4 字节 → " + new String(four, StandardCharsets.US_ASCII), buf);
        buf.compact();
        show("compact()", buf);
        ByteBuffer forgot = ByteBuffer.allocate(16); forgot.put("9527".getBytes(StandardCharsets.US_ASCII));
        System.out.println("  忘了 flip 就去读：还剩 " + forgot.remaining() + " 字节可读，读到的是没写过的 0");
        ByteBuffer direct = ByteBuffer.allocateDirect(16);
        System.out.println("  allocate 是堆内的吗？" + !buf.isDirect() + "，有 array 吗？" + buf.hasArray() + "；allocateDirect：isDirect=" + direct.isDirect() + "，hasArray=" + direct.hasArray());
        CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(kb));
        System.out.println("  decode 回字符：" + cb + "（" + kb.length + " 字节 → " + cb.length() + " 个 char）");
    }

    static void show(String what, ByteBuffer b) {
        System.out.printf("  %-24s position=%2d limit=%2d capacity=%2d remaining=%2d%n", what, b.position(), b.limit(), b.capacity(), b.remaining());
    }
}
