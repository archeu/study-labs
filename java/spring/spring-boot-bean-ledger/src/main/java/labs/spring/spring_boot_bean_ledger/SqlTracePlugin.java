package labs.spring.spring_boot_bean_ledger;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 第 9 站：MyBatis 插件，拦在 SQL 发给驱动的前一刻。在这里抓整条调用栈。 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "update", args = Statement.class))
public class SqlTracePlugin implements Interceptor {
    @Override
    public Object intercept(Invocation inv) throws Throwable {
        var sh = (StatementHandler) inv.getTarget();
        var st = (Statement) inv.getArgs()[0];
        String sql = sh.getBoundSql().getSql().replaceAll("\\s+", " ");
        int con = System.identityHashCode(st.getConnection());
        boolean tx = TransactionSynchronizationManager.isActualTransactionActive();
        Trace.ev("⑨ 发SQL", sql + "  参数=" + sh.getBoundSql().getParameterObject()
                + "，连接#" + con + " autoCommit=" + st.getConnection().getAutoCommit() + " 事务中=" + tx);
        if (!Trace.FACTS.containsKey("stack")) {
            List<String> frames = new ArrayList<>();
            for (var f : Thread.currentThread().getStackTrace()) frames.add(f.getClassName() + "." + f.getMethodName());
            Trace.FACTS.put("stack", frames);
            Trace.FACTS.put("sql.con", con);
        }
        Trace.mark("Q0");
        long t0 = System.nanoTime();
        try { return inv.proceed(); }
        finally { Trace.mark("Q1"); Trace.FACTS.merge("sql.nanos", System.nanoTime() - t0, (a, b) -> (Long) a + (Long) b); }
    }
}
