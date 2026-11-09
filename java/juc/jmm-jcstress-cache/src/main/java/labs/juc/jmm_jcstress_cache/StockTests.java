package labs.juc.jmm_jcstress_cache;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.atomic.AtomicInteger;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * 全景第 4、5 站：88 号只剩 1 件，exec-17 和 exec-23 同时扣。
 * 结果 (r1, r2)：1 表示这个线程认为自己抢到了。(1, 1) 就是一件卖给了两个人。
 */
public class StockTests {

    /** 普通 int：读、判断、写回分三步。 */
    @JCStressTest
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "只有一个人抢到")
    @Outcome(id = "1, 1", expect = ACCEPTABLE_INTERESTING, desc = "超卖：两个人都抢到了")
    @State
    public static class Plain {
        int left = 1;

        @Actor public void exec17(II_Result r) { if (left >= 1) { left = left - 1; r.r1 = 1; } }
        @Actor public void exec23(II_Result r) { if (left >= 1) { left = left - 1; r.r2 = 1; } }
    }

    /** volatile int：每次读都是最新的，但三步之间照样能插队。 */
    @JCStressTest
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "只有一个人抢到")
    @Outcome(id = "1, 1", expect = ACCEPTABLE_INTERESTING, desc = "超卖：volatile 也没用")
    @State
    public static class Volatile {
        volatile int left = 1;

        @Actor public void exec17(II_Result r) { if (left >= 1) { left = left - 1; r.r1 = 1; } }
        @Actor public void exec23(II_Result r) { if (left >= 1) { left = left - 1; r.r2 = 1; } }
    }

    /** CAS：比较和写入是一条原子指令。 */
    @JCStressTest
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "只有一个人抢到")
    @Outcome(id = "1, 1", expect = FORBIDDEN, desc = "超卖：CAS 下不允许出现")
    @State
    public static class Cas {
        final AtomicInteger left = new AtomicInteger(1);

        @Actor public void exec17(II_Result r) { r.r1 = take(); }
        @Actor public void exec23(II_Result r) { r.r2 = take(); }

        int take() {
            for (;;) {
                int cur = left.get();
                if (cur < 1) return 0;
                if (left.compareAndSet(cur, cur - 1)) return 1;
            }
        }
    }
}
