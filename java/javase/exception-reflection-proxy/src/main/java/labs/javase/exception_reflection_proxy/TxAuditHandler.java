package labs.javase.exception_reflection_proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 一个手写的“事务 + 审计”拦截器：Spring 的 @Transactional 背后就是这个形状。 */
class TxAuditHandler implements InvocationHandler {
    private final Object target;
    TxAuditHandler(Object target) { this.target = target; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Audit audit = method.getAnnotation(Audit.class);          // 接口方法上的注解
        String name = audit == null ? method.getName() : audit.value();
        System.out.println("    [代理] 开事务，审计：" + name);
        try {
            Object r = method.invoke(target, args);               // 反射调真正的对象
            System.out.println("    [代理] 提交");
            return r;
        } catch (InvocationTargetException e) {                   // 目标方法抛的异常被包了一层
            Throwable cause = e.getCause();
            boolean rollback = cause instanceof RuntimeException || cause instanceof Error;
            System.out.println("    [代理] 捕获 " + cause.getClass().getSimpleName() + " → " + (rollback ? "回滚" : "受检异常，照样提交（和 @Transactional 默认规则一样）"));
            throw cause;                                          // 拆开再抛，调用方看到的是原异常
        }
    }
}
