package labs.spring.spring_bean_proxy_swap;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 小工具：当前线程上有没有真事务、一个对象的类名（代理类名里带 $$SpringCGLIB$$）。 */
final class Tx {
    static boolean active() { return TransactionSynchronizationManager.isActualTransactionActive(); }
    static String cls(Object o) { return o == null ? "null" : o.getClass().getSimpleName(); }
    static void log(String s) { System.out.println("  " + s); }
}
