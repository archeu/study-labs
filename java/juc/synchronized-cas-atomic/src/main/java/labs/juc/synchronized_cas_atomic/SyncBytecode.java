package labs.juc.synchronized_cas_atomic;

/** 给 javap 看：同步块编译成 monitorenter / monitorexit，同步方法只多一个 ACC_SYNCHRONIZED 标志。 */
public class SyncBytecode {
    private final Object lock = new Object();
    private int left = 100;

    int takeBlock() {
        synchronized (lock) {
            if (left < 1) return 0;
            left--;
            return 1;
        }
    }

    synchronized int takeMethod() {
        if (left < 1) return 0;
        left--;
        return 1;
    }
}
