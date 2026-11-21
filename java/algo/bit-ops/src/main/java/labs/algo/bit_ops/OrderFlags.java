package labs.algo.bit_ops;

import java.util.EnumSet;

/**
 * 第 1 课补充 · 位运算与逻辑运算。
 * 1) 订单的 6 个开关属性装进一个 int：置位、清位、翻转、测试、“全都有 / 有任一个”
 * 2) EnumSet 内部就是同一件事（RegularEnumSet 一个 long）
 * 3) 移位、符号扩展、优先级、短路这些坑，每条打印出来
 * 4) 几个常用技巧：x & (x-1)、x & -x、2 的幂、tableSizeFor、用掩码枚举子集
 */
public class OrderFlags {
    // 每个开关占一位。位的位置一旦存进数据库或发到网络，就是协议，不能再改
    static final int IOC         = 1 << 0;  // 立即成交剩余撤销
    static final int FOK         = 1 << 1;  // 全部成交否则撤销
    static final int POST_ONLY   = 1 << 2;  // 只做挂单方
    static final int REDUCE_ONLY = 1 << 3;  // 只减仓
    static final int ICEBERG     = 1 << 4;  // 冰山单
    static final int HIDDEN      = 1 << 5;  // 隐藏单

    enum Flag { IOC, FOK, POST_ONLY, REDUCE_ONLY, ICEBERG, HIDDEN }

    static String bin(int x) {
        String s = Integer.toBinaryString(x & 0xFF);
        while (s.length() < 8) s = "0" + s;
        return s.substring(0, 4) + "_" + s.substring(4);
    }

    static String bin32(int x) {
        StringBuilder sb = new StringBuilder();
        for (int i = 31; i >= 0; i--) { sb.append((x >>> i) & 1); if (i % 8 == 0 && i > 0) sb.append('_'); }
        return sb.toString();
    }

    public static void main(String[] args) {
        int f = 0;
        f |= IOC;                System.out.println("置位 IOC          f |= IOC          → " + bin(f));
        f |= POST_ONLY;          System.out.println("置位 POST_ONLY    f |= POST_ONLY    → " + bin(f));
        System.out.println("测试 FOK          (f & FOK) != 0    → " + ((f & FOK) != 0));
        System.out.println("测试 POST_ONLY    (f & POST_ONLY) != 0 → " + ((f & POST_ONLY) != 0));
        f &= ~IOC;               System.out.println("清位 IOC          f &= ~IOC         → " + bin(f) + "（~IOC = " + bin(~IOC) + "）");
        f ^= REDUCE_ONLY;        System.out.println("翻转 REDUCE_ONLY  f ^= REDUCE_ONLY  → " + bin(f));
        f ^= REDUCE_ONLY;        System.out.println("再翻转一次                         → " + bin(f));
        int need = POST_ONLY | HIDDEN;
        System.out.println("全都有？(f & need) == need → " + ((f & need) == need) + "；有任一个？(f & need) != 0 → " + ((f & need) != 0));
        int g = f | ICEBERG | HIDDEN;
        System.out.println("再开 ICEBERG、HIDDEN：g = " + bin(g) + "，开了几个 Integer.bitCount(g) = " + Integer.bitCount(g));

        EnumSet<Flag> es = EnumSet.of(Flag.POST_ONLY, Flag.HIDDEN);
        System.out.println("EnumSet " + es + " contains(FOK) = " + es.contains(Flag.FOK) + "（内部是一个 long，同样的 & 和 |）");

        System.out.println();
        System.out.println("—— 移位 ——");
        System.out.println("1 << 31 = " + (1 << 31) + "，1 << 32 = " + (1 << 32) + "，1 << 33 = " + (1 << 33) + "，1L << 32 = " + (1L << 32));
        System.out.println("-8 >> 1 = " + (-8 >> 1) + "，-8 >>> 1 = " + (-8 >>> 1));
        System.out.println("  -8      = " + bin32(-8));
        System.out.println("  -8 >> 1 = " + bin32(-8 >> 1));
        System.out.println("  -8 >>>1 = " + bin32(-8 >>> 1));
        System.out.println("-7 >> 1 = " + (-7 >> 1) + "，-7 / 2 = " + (-7 / 2) + "，Math.floorDiv(-7, 2) = " + Math.floorDiv(-7, 2));
        byte b = (byte) 0xF0;
        System.out.println("byte b = 0xF0（" + b + "）：b >>> 4 = " + (b >>> 4) + "，(b & 0xFF) >>> 4 = " + ((b & 0xFF) >>> 4));
        int x = 5;
        System.out.println("x = 5：x << 1 + 1 = " + (x << 1 + 1) + "（先算 1 + 1），(x << 1) + 1 = " + ((x << 1) + 1));

        System.out.println();
        System.out.println("—— 逻辑运算：短路 ——");
        String s = null;
        System.out.println("s != null && s.length() > 0 → " + (s != null && s.length() > 0));
        try {
            System.out.println("s != null & s.length() > 0 → " + (s != null & s.length() > 0));
        } catch (NullPointerException e) {
            System.out.println("s != null & s.length() > 0 → NullPointerException（& 两边都算）");
        }
        int[] calls = {0};
        boolean r1 = false && check(calls), r2 = false & check(calls);
        System.out.println("false && check() 不调 check，false & check() 要调：check 一共被调用 " + calls[0] + " 次（结果都是 " + r1 + " / " + r2 + "）");
        System.out.println("true ^ true = " + (true ^ true) + "（布尔异或 = 不相等）");

        System.out.println();
        System.out.println("—— 常用技巧 ——");
        int v = 0b1011_0100;
        System.out.println("v           = " + bin(v));
        System.out.println("v & (v - 1) = " + bin(v & (v - 1)) + "  去掉最低位的 1");
        System.out.println("v & -v      = " + bin(v & -v) + "  只留最低位的 1（lowbit）");
        int cnt = 0;
        for (int t = v; t != 0; t &= t - 1) cnt++;
        System.out.println("循环 t &= t - 1 数 1 的个数 = " + cnt + "，Integer.bitCount = " + Integer.bitCount(v));
        for (int n : new int[]{64, 96, 1, 0}) System.out.print(n + " 是 2 的幂？" + (n > 0 && (n & (n - 1)) == 0) + "  ");
        System.out.println();
        for (int cap : new int[]{10, 16, 17, 100}) System.out.print("tableSizeFor(" + cap + ") = " + tableSizeFor17(cap) + "/" + tableSizeFor8(cap) + "  ");
        System.out.println("（JDK 17 写法 / JDK 8 写法）");
        System.out.println("182 % 16 = " + (182 % 16) + "，182 & 15 = " + (182 & 15) + "；-182 % 16 = " + (-182 % 16) + "，-182 & 15 = " + (-182 & 15));

        String[] st = {"趋势", "网格", "套利"};
        StringBuilder sb = new StringBuilder("3 个策略的全部子集：");
        for (int mask = 0; mask < (1 << st.length); mask++) {
            sb.append("{");
            for (int i = 0; i < st.length; i++) if ((mask >> i & 1) == 1) sb.append(st[i]);
            sb.append("}");
        }
        System.out.println(sb);
        int a = 3, c = 9;
        a ^= c; c ^= a; a ^= c;
        System.out.println("异或交换：a = " + a + "，c = " + c + "；异或找“唯一落单的单号”：" + (1024 ^ 2048 ^ 9527 ^ 1024 ^ 2048));
    }

    static boolean check(int[] calls) { calls[0]++; return true; }

    static int tableSizeFor17(int cap) {
        int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
        return (n < 0) ? 1 : (n >= (1 << 30)) ? (1 << 30) : n + 1;
    }

    static int tableSizeFor8(int cap) {
        int n = cap - 1;
        n |= n >>> 1; n |= n >>> 2; n |= n >>> 4; n |= n >>> 8; n |= n >>> 16;
        return (n < 0) ? 1 : (n >= (1 << 30)) ? (1 << 30) : n + 1;
    }
}
