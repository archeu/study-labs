package labs.juc.jmm_jcstress_cache;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/** 旧页 ↺ 里“没在本机跑过”的几个预期，这里真跑。 */
public class ReorderTests {

    /* ---------- SB：两个线程都先写自己的、再读对方的 ---------- */

    @JCStressTest
    @Outcome(id = {"0, 1", "1, 0", "1, 1"}, expect = ACCEPTABLE, desc = "按某种交错能解释")
    @Outcome(id = "0, 0", expect = ACCEPTABLE_INTERESTING, desc = "两边都没看到对方的写：写还在写缓冲里")
    @State
    public static class SbPlain {
        int x, y;
        @Actor public void a(II_Result r) { x = 1; r.r1 = y; }
        @Actor public void b(II_Result r) { y = 1; r.r2 = x; }
    }

    /** 只让 x 是 volatile：b 那边是普通写 y、普通读 x，它自己的“先写后读”还能被重排。 */
    @JCStressTest
    @Outcome(id = {"0, 1", "1, 0", "1, 1"}, expect = ACCEPTABLE, desc = "按某种交错能解释")
    @Outcome(id = "0, 0", expect = ACCEPTABLE_INTERESTING, desc = "只有一边 volatile，照样会出现")
    @State
    public static class SbOneVolatile {
        volatile int x;
        int y;
        @Actor public void a(II_Result r) { x = 1; r.r1 = y; }
        @Actor public void b(II_Result r) { y = 1; r.r2 = x; }
    }

    @JCStressTest
    @Outcome(id = {"0, 1", "1, 0", "1, 1"}, expect = ACCEPTABLE, desc = "按某种交错能解释")
    @Outcome(id = "0, 0", expect = FORBIDDEN, desc = "两边都 volatile：JMM 禁止")
    @State
    public static class SbBothVolatile {
        volatile int x, y;
        @Actor public void a(II_Result r) { x = 1; r.r1 = y; }
        @Actor public void b(II_Result r) { y = 1; r.r2 = x; }
    }

    /* ---------- MP：先写数据、再写标志；另一边先读标志、再读数据 ---------- */

    @JCStressTest
    @Outcome(id = {"0, 0", "0, 1", "1, 1"}, expect = ACCEPTABLE, desc = "按某种交错能解释")
    @Outcome(id = "1, 0", expect = ACCEPTABLE_INTERESTING, desc = "看到了标志，却没看到数据")
    @State
    public static class MpPlain {
        int data, ready;
        @Actor public void writer() { data = 1; ready = 1; }
        @Actor public void reader(II_Result r) { r.r1 = ready; r.r2 = data; }
    }

    @JCStressTest
    @Outcome(id = {"0, 0", "0, 1", "1, 1"}, expect = ACCEPTABLE, desc = "按某种交错能解释")
    @Outcome(id = "1, 0", expect = FORBIDDEN, desc = "标志是 volatile：看到标志就一定看到数据")
    @State
    public static class MpVolatile {
        int data;
        volatile int ready;
        @Actor public void writer() { data = 1; ready = 1; }
        @Actor public void reader(II_Result r) { r.r1 = ready; r.r2 = data; }
    }

    /* ---------- volatile 计数：两个线程各 ++ 一次 ---------- */

    @JCStressTest
    @Outcome(id = "2", expect = ACCEPTABLE, desc = "两次都加上了")
    @Outcome(id = "1", expect = ACCEPTABLE_INTERESTING, desc = "丢了一次：volatile 不管读-改-写")
    @State
    public static class VolatileIncrement {
        volatile int count;
        @Actor public void a() { count++; }
        @Actor public void b() { count++; }
        @org.openjdk.jcstress.annotations.Arbiter public void check(I_Result r) { r.r1 = count; }
    }
}
