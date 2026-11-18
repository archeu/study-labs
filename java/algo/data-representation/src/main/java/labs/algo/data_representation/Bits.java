package labs.algo.data_representation;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 计算机怎样表示数据：把页面⑤手推的每一步，用 Java 打印出来对答案。
 * 按 JDK 8 写法，--release 8 编译。
 */
public class Bits {

    /** 把 int 的低 n 位按 4 位一组打出来，例如 bin(13, 8) = "0000 1101" */
    static String bin(long v, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = n - 1; i >= 0; i--) {
            sb.append((v >>> i) & 1);
            if (i % 4 == 0 && i > 0) sb.append(' ');
        }
        return sb.toString();
    }

    static String hex(byte[] bs) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bs) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        System.out.println("== 1 整数：二进制与补码 ==");
        System.out.println("13          = " + bin(13, 8));
        System.out.println("~13         = " + bin(~13, 8) + "   (每一位取反)");
        System.out.println("-13 = ~13+1 = " + bin(-13, 8) + "   = 0x" + Integer.toHexString(-13 & 0xFF).toUpperCase());
        System.out.println("13 + (-13)  = " + bin(13 + (-13), 8) + "   (第 9 位的进位被丢掉)");

        System.out.println("\n== 2 位数有限：溢出就是绕圈 ==");
        System.out.println("Integer.MAX_VALUE + 1   = " + (Integer.MAX_VALUE + 1));
        System.out.println("Math.abs(MIN_VALUE)     = " + Math.abs(Integer.MIN_VALUE));
        System.out.println("(byte) 200              = " + (byte) 200);
        System.out.println("(byte) 200 & 0xFF       = " + (((byte) 200) & 0xFF));
        try {
            Math.addExact(Integer.MAX_VALUE, 1);
        } catch (ArithmeticException e) {
            System.out.println("Math.addExact(MAX, 1)   -> " + e.getMessage());
        }

        System.out.println("\n== 3 小数：0.1 存不准 ==");
        System.out.println("0.1 + 0.2               = " + (0.1 + 0.2));
        long b = Double.doubleToLongBits(0.1);
        System.out.println("0.1 的 64 位            = " + bin(b >>> 63, 1) + " | " + bin((b >>> 52) & 0x7FF, 11) + " | "
                + Long.toHexString(b & 0xFFFFFFFFFFFFFL).toUpperCase());
        System.out.println("new BigDecimal(0.1)     = " + new BigDecimal(0.1));
        System.out.println("new BigDecimal(\"0.1\")   = " + new BigDecimal("0.1"));
        double big = 9007199254740993.0; // 2^53 + 1
        System.out.println("(double) (2^53 + 1)     = " + String.format("%.0f", big));

        System.out.println("\n== 4 文字：码点 → 字节 ==");
        String[] samples = {"A", "é", "你", "😀"};
        for (String s : samples) {
            System.out.printf("%s  U+%04X  UTF-8: %-12s  length()=%d  码点数=%d%n",
                    s, s.codePointAt(0), hex(s.getBytes(StandardCharsets.UTF_8)),
                    s.length(), s.codePointCount(0, s.length()));
        }
        byte[] nihao = "你好".getBytes(StandardCharsets.UTF_8);
        System.out.println("\"你好\" 的 UTF-8 字节     = " + hex(nihao));
        System.out.println("  按 GBK 去读           = " + new String(nihao, Charset.forName("GBK")));
        System.out.println("  按 ISO-8859-1 去读    = " + new String(nihao, StandardCharsets.ISO_8859_1));
        System.out.println("  按 UTF-8 去读         = " + new String(nihao, StandardCharsets.UTF_8));

        System.out.println("\n== 5 同样 4 个字节，换个读法 ==");
        byte[] four = {0x41, 0x42, 0x43, 0x44};
        System.out.println("字节                    = " + hex(four));
        System.out.println("当 int（大端）          = " + ByteBuffer.wrap(four).getInt());
        System.out.println("当 int（小端）          = " + ByteBuffer.wrap(four).order(ByteOrder.LITTLE_ENDIAN).getInt());
        System.out.println("当 float（大端）        = " + ByteBuffer.wrap(four).getFloat());
        System.out.println("当文字（ASCII）         = " + new String(four, StandardCharsets.US_ASCII));

        System.out.println("\n== 6 连到算法：二分查找的中点 ==");
        int lo = 1_500_000_000, hi = 2_000_000_000;
        System.out.println("(lo + hi) / 2           = " + (lo + hi) / 2);
        System.out.println("(lo + hi) >>> 1         = " + ((lo + hi) >>> 1));
        System.out.println("100 万个元素最多比几次  = " + (32 - Integer.numberOfLeadingZeros(1_000_000)) + "  (= 1000000 的二进制位数)");
    }
}
