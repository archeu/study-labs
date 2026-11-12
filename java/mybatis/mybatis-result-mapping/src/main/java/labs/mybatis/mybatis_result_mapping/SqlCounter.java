package labs.mybatis.mybatis_result_mapping;

import java.sql.Connection;
import java.util.*;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;

/**
 * 插件挂在 StatementHandler.prepare 上：每准备一条 SQL 记一次，
 * 顺便记下用的是哪个连接对象。HikariCP 每借一次连接都给一个新的代理对象，所以“不同的代理个数”就是借了几次。
 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class SqlCounter implements Interceptor {
    static int sql;
    static final Set<Integer> conns = new HashSet<>();
    static final List<String> first = new ArrayList<>();

    static void reset() { sql = 0; conns.clear(); first.clear(); }

    @Override
    public Object intercept(Invocation inv) throws Throwable {
        sql++;
        conns.add(System.identityHashCode(inv.getArgs()[0]));
        if (first.size() < 3) {
            String s = ((StatementHandler) inv.getTarget()).getBoundSql().getSql().replaceAll("\\s+", " ").trim();
            first.add(s.length() > 70 ? s.substring(0, 70) + "…" : s);
        }
        return inv.proceed();
    }
}
