package labs.spring.spring_aop_chain;

/** 打印调用栈里夹在“调用方”和“目标方法”之间的那几层。 */
final class StackDump {
    static boolean enabled = false;

    static void between(String callerClass, String target) {
        if (!enabled) return;
        StackTraceElement[] st = new Throwable().getStackTrace();
        int me = 1, caller = -1;
        for (int i = 1; i < st.length; i++) {
            if (st[i].getClassName().endsWith("." + callerClass)) { caller = i; break; }
        }
        System.out.println("    调用栈（从调用方往里，目标方法在最下面）：");
        if (caller < 0) { System.out.println("    找不到 " + callerClass); return; }
        System.out.printf("       %s.%s%n", simple(st[caller].getClassName()), st[caller].getMethodName());
        int n = 0;
        for (int i = caller - 1; i > me; i--) {
            n++;
            System.out.printf("    %2d %s.%s%n", n, simple(st[i].getClassName()), st[i].getMethodName());
        }
        System.out.printf("       %s   ← 中间夹了 %d 层%n", target, n);
    }

    private static String simple(String cn) {
        int dot = cn.lastIndexOf('.');
        return dot < 0 ? cn : cn.substring(dot + 1);
    }
}
