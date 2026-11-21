package labs.algo.signed_codes;

/**
 * 第 1 课补充 · 原码、反码、补码。
 * 1) 4 位：把 16 种图案按三种约定各读一遍
 * 2) 三种约定下做同一组加法，看谁直接加就对
 * 3) Java 里的整数只有补码：-x == ~x + 1 对全部 2^32 个 int 验证一遍
 * 4) 补码的边角：最小值没有相反数、溢出绕圈、byte 截断
 * 5) 原码今天还在哪：double 的符号位（+0.0 和 -0.0）
 */
public class SignedCodes {
    static final int N = 4, MASK = (1 << N) - 1, SIGN = 1 << (N - 1);

    static String bits(int p) {
        String s = Integer.toBinaryString(p & MASK);
        while (s.length() < N) s = "0" + s;
        return s;
    }

    /** 同一个 4 位图案，按三种约定读成十进制。返回 null 表示“−0”。 */
    static String readSignMagnitude(int p) { int m = p & (SIGN - 1); return (p & SIGN) == 0 ? "" + m : (m == 0 ? "−0" : "−" + m); }
    static String readOnes(int p) { if ((p & SIGN) == 0) return "" + p; int m = (~p) & MASK; return m == 0 ? "−0" : "−" + m; }
    static String readTwos(int p) { return (p & SIGN) == 0 ? "" + p : "−" + ((1 << N) - p); }

    /** 三种约定把十进制 x 写成 4 位图案。 */
    static int encSignMagnitude(int x) { return x >= 0 ? x : (SIGN | -x); }
    static int encOnes(int x) { return x >= 0 ? x : (~(-x)) & MASK; }
    static int encTwos(int x) { return x & MASK; }

    public static void main(String[] args) {
        System.out.println("4 位图案   原码   反码   补码   无符号");
        for (int p = 0; p < 16; p++)
            System.out.printf("  %s     %4s   %4s   %4s   %4d%n", bits(p), readSignMagnitude(p), readOnes(p), readTwos(p), p);

        System.out.println();
        System.out.println("−3 的三种写法：+3 = " + bits(3) + "；原码 " + bits(encSignMagnitude(-3))
                + "（符号位改 1）；反码 " + bits(encOnes(-3)) + "（+3 每位取反）；补码 " + bits(encTwos(-3)) + "（反码再 + 1）");
        System.out.println("反码 = 15 − 3 = 12 = " + bits(12) + "；补码 = 16 − 3 = 13 = " + bits(13));

        System.out.println();
        System.out.println("同一组加法，加法器只会“按位相加、超出 4 位的进位丢掉”：");
        int[][] cases = {{3, -3}, {5, -3}, {2, -5}, {-2, -3}, {6, 3}};
        for (int[] c : cases) {
            int a = c[0], b = c[1], want = a + b;
            int sm = (encSignMagnitude(a) + encSignMagnitude(b)) & MASK;
            int raw1 = encOnes(a) + encOnes(b), on = raw1 & MASK;
            int onFixed = (on + (raw1 >> N)) & MASK;          // 反码：溢出的进位要绕回来加到最低位
            int tw = (encTwos(a) + encTwos(b)) & MASK;
            System.out.printf("  %2d + %2d = %2d │ 原码 %s→%-3s │ 反码 %s→%-3s 绕回进位后 %s→%-3s │ 补码 %s→%-3s%n",
                    a, b, want, bits(sm), readSignMagnitude(sm), bits(on), readOnes(on), bits(onFixed), readOnes(onFixed), bits(tw), readTwos(tw));
        }

        System.out.println();
        System.out.println("Java 的 int 就是 32 位补码：");
        System.out.println("  -3 = " + Integer.toBinaryString(-3) + "，~3 + 1 = " + (~3 + 1));
        long t0 = System.nanoTime();
        long bad = 0;
        int x = Integer.MIN_VALUE;
        while (true) {
            if (-x != ~x + 1) bad++;
            if (x == Integer.MAX_VALUE) break;
            x++;
        }
        System.out.println("  全部 4294967296 个 int 都满足 -x == ~x + 1？不满足的有 " + bad + " 个（" + (System.nanoTime() - t0) / 1000000 + " ms）");
        System.out.println("  Integer.MIN_VALUE = " + Integer.MIN_VALUE + "，-MIN_VALUE = " + (-Integer.MIN_VALUE) + "，Math.abs(MIN_VALUE) = " + Math.abs(Integer.MIN_VALUE));
        System.out.println("  Integer.MAX_VALUE + 1 = " + (Integer.MAX_VALUE + 1));
        int p8 = 0b1000;
        System.out.println("  4 位的 1000 取反加一 = " + bits(((~p8) + 1) & MASK) + "，8 + 8 = " + ((8 + 8) & MASK) + "：1000 的相反数是它自己，站在 0 的正对面");
        System.out.println("  MIN_VALUE < 0 → " + (Integer.MIN_VALUE < 0) + "，MIN_VALUE >>> 31 = " + (Integer.MIN_VALUE >>> 31) + "（最高位就是符号，没有例外）");
        System.out.println("  (byte) 0x80 = " + (byte) 0x80 + "，扩成 int = 0x" + Integer.toHexString((byte) 0x80) + "（复制最高位扩展，−128 还是 −128）");
        byte b1 = 100, b2 = 100;
        System.out.println("  byte：100 + 100 存回 byte = " + (byte) (b1 + b2) + "，(byte) 200 = " + (byte) 200 + "，(byte) 0xFF = " + (byte) 0xFF + "，0b1111_1111 = " + 0b1111_1111);
        System.out.println("  byte 的 -1 拿去当 int 用 = " + (int) (byte) -1 + "，按无符号读 Byte.toUnsignedInt = " + Byte.toUnsignedInt((byte) -1));

        System.out.println();
        System.out.println("原码今天还在哪：double 的符号位");
        double pz = 0.0, nz = -0.0;
        System.out.println("  0.0 == -0.0 → " + (pz == nz) + "；Double.valueOf(0.0).equals(-0.0) → " + Double.valueOf(pz).equals(nz)
                + "；1 / 0.0 = " + (1 / pz) + "，1 / -0.0 = " + (1 / nz));
        System.out.println("  -0.0 的位 = " + Long.toBinaryString(Double.doubleToRawLongBits(nz)) + "（只有符号位是 1）");
    }
}
