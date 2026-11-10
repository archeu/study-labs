package labs.jvm.jvm_jit_escape;

import java.lang.management.ManagementFactory;

/** 读当前线程在堆上累计分配了多少字节（HotSpot 的 com.sun.management 扩展）。 */
final class Alloc {
    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static long bytes() { return MX.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    private Alloc() {}
}
