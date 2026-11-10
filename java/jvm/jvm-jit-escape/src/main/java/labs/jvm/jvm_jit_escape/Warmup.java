package labs.jvm.jvm_jit_escape;

/**
 * 全景第 2 站：算价方法从解释执行，到 C1，再到 C2。
 * 每批 20 万次 priceOf，打印这一批的 ns/次 和 每次在堆上分配的字节。
 *
 *   java -cp target/classes labs.jvm.jvm_jit_escape.Warmup                       # 默认：分层编译
 *   java -Xint -cp target/classes labs.jvm.jvm_jit_escape.Warmup                 # 只解释
 *   java -XX:TieredStopAtLevel=1 -cp target/classes labs.jvm.jvm_jit_escape.Warmup   # 只到 C1：没有逃逸分析
 *   java -XX:-DoEscapeAnalysis -cp target/classes labs.jvm.jvm_jit_escape.Warmup
 *   java -XX:+PrintCompilation -cp target/classes labs.jvm.jvm_jit_escape.Warmup | grep -E "priceOf|batch"
 */
public class Warmup {
    static final long[] BASE = new long[1024];
    static { for (int i = 0; i < BASE.length; i++) BASE[i] = 1000 + i * 7; }

    // 一个订单行的价格：单价 × 数量，满 10 件打 95 折。三个 Money 都没逃出这个方法。
    static long priceOf(int sku, int qty) {
        Money unit = new Money(BASE[sku & 1023]);
        return unit.times(qty).off(qty >= 10 ? 5 : 0).cents;
    }

    static long batch(int n, int seed) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += priceOf(i + seed, (i & 15) + 1);
        return sum;
    }

    public static void main(String[] args) {
        int batches = Integer.getInteger("batches", 30), n = 200_000;
        long sink = 0;
        long t0 = System.nanoTime();
        for (int b = 1; b <= batches; b++) {
            long a0 = Alloc.bytes(), s = System.nanoTime();
            sink += batch(n, b);
            long ns = System.nanoTime() - s, bytes = Alloc.bytes() - a0;
            System.out.printf("批 %2d  %7.1f ns/次  %5.1f B/次  累计 %6.1f ms%n",
                    b, ns / (double) n, bytes / (double) n, (System.nanoTime() - t0) / 1e6);
        }
        System.out.println("sink=" + sink);
    }
}
