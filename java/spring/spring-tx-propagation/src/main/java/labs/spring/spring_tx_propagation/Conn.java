package labs.spring.spring_tx_propagation;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 看一眼当前线程上绑着哪条连接：MyBatis 也是经 DataSourceUtils 从同一格里拿的。 */
final class Conn {
    private Conn() {}

    static String peek(DataSource ds) {
        Connection c = DataSourceUtils.getConnection(ds);
        try {
            Connection raw = c.unwrap(Connection.class);
            return "连接#" + Integer.toHexString(System.identityHashCode(raw))
                    + " 隔离=" + iso(c.getTransactionIsolation())
                    + " 事务名=" + TransactionSynchronizationManager.getCurrentTransactionName();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            DataSourceUtils.releaseConnection(c, ds);
        }
    }

    static String iso(int level) {
        return switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> String.valueOf(level);
        };
    }
}
