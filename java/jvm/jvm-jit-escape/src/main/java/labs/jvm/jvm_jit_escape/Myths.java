package labs.jvm.jvm_jit_escape;

/**
 * 两个常见误区，现场看：
 *  1. 手写微基准：结果没用上，C2 把整个循环删了，“快得离谱”。
 *  2. 热点上反复抛同一个 NPE，C2 换成预先建好的异常对象，日志里没有堆栈。
 *
 *   java -cp target/classes labs.jvm.jvm_jit_escape.Myths
 *   java -XX:-OmitStackTraceInFastThrow -cp target/classes labs.jvm.jvm_jit_escape.Myths
 */
public class Myths {
    static final class Coupon { final String code; Coupon(String c) { code = c; } }
    static final class Order { final long id; final Coupon coupon; Order(long id, Coupon c) { this.id = id; coupon = c; } }

    static long priceOf(int i) { return new Money(1000 + (i & 1023)).times((i & 7) + 1).cents; }

    static long discard(int n) { for (int i = 0; i < n; i++) priceOf(i); return 0; }
    static long consume(int n) { long s = 0; for (int i = 0; i < n; i++) s += priceOf(i); return s; }

    static int couponLen(Order o) { return o.coupon.code.length(); }   // coupon 为 null 时 NPE

    public static void main(String[] args) {
        int n = 50_000_000;
        System.out.println("① 手写微基准（每轮 5000 万次 priceOf）");
        long keep = 0;
        for (int round = 1; round <= 5; round++) {
            long s = System.nanoTime(); discard(n); long d = System.nanoTime() - s;
            s = System.nanoTime(); keep += consume(n); long c = System.nanoTime() - s;
            System.out.printf("  第 %d 轮  结果丢掉 %7.2f ms   结果用上 %7.2f ms%n", round, d / 1e6, c / 1e6);
        }
        System.out.println("  keep=" + keep);

        System.out.println("② 同一行代码反复 NPE：异常的堆栈有几层");
        Order noCoupon = new Order(9527, null);
        int firstEmpty = -1;
        for (int i = 1; i <= 200_000; i++) {
            try { couponLen(noCoupon); }
            catch (NullPointerException e) {
                int depth = e.getStackTrace().length;
                if (i == 1) System.out.printf("  第 1 次：%d 层，message=%s%n", depth, e.getMessage());
                if (depth == 0 && firstEmpty < 0) { firstEmpty = i; System.out.printf("  第 %d 次起：0 层，message=%s%n", i, e.getMessage()); }
            }
        }
        if (firstEmpty < 0) System.out.println("  20 万次里堆栈一直都在");
    }
}
