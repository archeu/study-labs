package labs.juc.synchronized_cas_atomic;

import org.openjdk.jol.info.ClassLayout;

import java.util.concurrent.CountDownLatch;

/**
 * 88 号商品的库存对象，看它的对象头在几种情况下长什么样。
 * 默认（JDK 17 没有偏向锁）：java ... MarkWord
 * 打开偏向锁对比：java -XX:+UseBiasedLocking -XX:BiasedLockingStartupDelay=0 ... MarkWord
 */
public class MarkWord {
    static class Stock { int left = 100; }

    public static void main(String[] args) throws Exception {
        final Stock stock = new Stock();
        show("1. 刚 new 出来", stock);

        synchronized (stock) {
            show("2. main 线程拿着锁，没人抢", stock);
        }
        show("3. 放开锁以后", stock);

        final CountDownLatch holding = new CountDownLatch(1);
        Thread exec17 = new Thread(() -> {
            synchronized (stock) {
                holding.countDown();
                sleep(300);                 // 拿着锁睡 300 ms
            }
        }, "exec-17");
        exec17.start();
        holding.await();
        Thread exec23 = new Thread(() -> { synchronized (stock) { stock.left--; } }, "exec-23");
        exec23.start();
        sleep(100);                         // exec-23 这时卡在锁上
        show("4. exec-17 拿着锁，exec-23 在门口等（" + exec23.getState() + "）", stock);
        exec17.join();
        exec23.join();
        show("5. 两个都走了", stock);

        Stock fresh = new Stock();
        int h = System.identityHashCode(fresh);
        show("6. 新对象，算过 identityHashCode = 0x" + Integer.toHexString(h), fresh);
        synchronized (fresh) {
            show("7. 再对它加锁", fresh);
        }
    }

    static void show(String title, Object o) {
        String layout = ClassLayout.parseInstance(o).toPrintable();
        for (String line : layout.split("\n")) {
            if (line.contains("(object header: mark)")) {
                System.out.printf("%-46s %s%n", title, line.substring(line.indexOf("0x")).trim());
            }
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
