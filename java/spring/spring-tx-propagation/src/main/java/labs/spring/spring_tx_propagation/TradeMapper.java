package labs.spring.spring_tx_propagation;

import org.apache.ibatis.annotations.*;

/** 交易靶场的三张表：订单、支付流水（失败也要留）、通知记录。 */
@Mapper
public interface TradeMapper {
    @Select("SELECT amount_cents FROM orders WHERE id = #{id}")
    long amountOf(long id);

    @Select("SELECT status FROM orders WHERE id = #{id}")
    String statusOf(long id);

    @Update("UPDATE orders SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int changeStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    @Update("UPDATE orders SET status = status WHERE id = #{id}")
    int touchOrder(long id);

    @Insert("INSERT INTO pay_log(order_id, result) VALUES (#{orderId}, #{result})")
    int insertPayLog(@Param("orderId") long orderId, @Param("result") String result);

    @Select("SELECT COUNT(*) FROM pay_log WHERE order_id = #{orderId}")
    int payLogCount(long orderId);

    @Insert("INSERT INTO notify_log(order_id, via) VALUES (#{orderId}, #{via})")
    int insertNotify(@Param("orderId") long orderId, @Param("via") String via);

    @Select("SELECT COUNT(*) FROM notify_log WHERE order_id = #{orderId} AND via = #{via}")
    int notifyCount(@Param("orderId") long orderId, @Param("via") String via);

    @Delete("DELETE FROM pay_log")
    void clearPayLog();

    @Delete("DELETE FROM notify_log")
    void clearNotify();

    @Update("MERGE INTO orders KEY(id) VALUES (#{id}, #{amount}, 'CREATED')")
    void resetOrder(@Param("id") long id, @Param("amount") long amount);
}
