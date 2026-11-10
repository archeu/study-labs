package labs.jvm.jvm_jit_escape;

/**
 * 逃逸分析判定：8 种写法，各自预热 300 万次，再量 1000 万次，每次在堆上分配多少字节。
 * 0 B 表示 C2 做了标量替换，new 没有真的发生；24 B 表示一个 Money 进了堆。
 *
 *   java -cp target/classes labs.jvm.jvm_jit_escape.Escape
 *   java -XX:-DoEscapeAnalysis -cp target/classes labs.jvm.jvm_jit_escape.Escape
 */
public class Escape {
    interface Channel { long fee(Money m); }
    static final class MockPay implements Channel { public long fee(Money m) { return m.cents / 100; } }
    static final class AliPay  implements Channel { public long fee(Money m) { return m.cents * 6 / 1000; } }
    static final class WxPay   implements Channel { public long fee(Money m) { return m.cents * 6 / 1000 + 1; } }
    static final Channel[] ONE = { new MockPay() };
    static final Channel[] THREE = { new MockPay(), new AliPay(), new WxPay() };

    static Money sinkField;
    static long[] big;

    // A 只在方法里用
    static long local(int i) { Money m = new Money(i); return m.times(2).cents; }
    // B 被返回：调用方存起来
    static Money returned(int i) { return new Money(i); }
    // C 存进静态字段
    static long stored(int i) { Money m = new Money(i); sinkField = m; return m.cents; }
    // D 传给接口方法，这个调用点只见过一种实现
    static long passMono(int i) { Money m = new Money(i); return ONE[0].fee(m); }
    // E 同一个接口，这个调用点见过三种实现
    static long passMega(int i) { Money m = new Money(i); return THREE[i % 3].fee(m); }
    // F 两条分支各 new 一个，合流后再用
    static long merged(int i) { Money m = (i & 1) == 0 ? new Money(i) : new Money(-i); return m.cents; }
    // G 小数组：长度是常量 4
    static long smallArray(int i) { long[] p = new long[4]; p[0] = i; p[3] = i * 2L; return p[0] + p[3]; }
    // H 数组长度 100，超过 EliminateAllocationArraySizeLimit（64）
    static long bigArray(int i) { long[] p = new long[100]; p[0] = i; p[99] = i * 2L; return p[0] + p[99]; }

    interface Case { long run(int i); }

    static void measure(String name, Case c) {
        long sink = 0;
        for (int i = 0; i < 3_000_000; i++) sink += c.run(i);
        int n = 10_000_000;
        long a0 = Alloc.bytes();
        for (int i = 0; i < n; i++) sink += c.run(i);
        System.out.printf("%-30s %6.1f B/次   (sink %d)%n", name, (Alloc.bytes() - a0) / (double) n, sink & 1);
    }

    public static void main(String[] args) {
        String only = System.getProperty("only", "");
        Object[][] cases = {
            {"A 只在方法里用", (Case) Escape::local},
            {"B 被返回", (Case) i -> { sinkField = returned(i); return 0; }},
            {"C 存进静态字段", (Case) Escape::stored},
            {"D 传参·调用点只见过 1 种实现", (Case) Escape::passMono},
            {"E 传参·调用点见过 3 种实现", (Case) Escape::passMega},
            {"F 两条分支各 new 再合流", (Case) Escape::merged},
            {"G 小数组 long[4]", (Case) Escape::smallArray},
            {"H 大数组 long[100]", (Case) Escape::bigArray},
        };
        for (Object[] c : cases)
            if (only.isEmpty() || ((String) c[0]).startsWith(only)) measure((String) c[0], (Case) c[1]);
    }
}
