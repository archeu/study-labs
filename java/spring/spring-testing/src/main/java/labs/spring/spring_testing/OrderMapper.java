package labs.spring.spring_testing;

import org.apache.ibatis.annotations.*;

/** 第 8、9 站：接口方法 → SQL。 */
@Mapper
public interface OrderMapper {
    record Order(long id, long userId, long amountCents, String status) {}

    @Select("SELECT id, user_id, amount_cents, status FROM orders WHERE id = #{id}")
    Order findById(long id);

    /** 带上旧状态做条件：重复付款时影响 0 行。 */
    @Update("UPDATE orders SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int changeStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    @Insert("INSERT INTO pay_log(order_id, amount_cents, channel_txn) VALUES(#{orderId}, #{amountCents}, #{channelTxn})")
    int insertLog(@Param("orderId") long orderId, @Param("amountCents") long amountCents, @Param("channelTxn") String channelTxn);

    @Insert("INSERT INTO orders VALUES (9527, 1024, 79800, 'CREATED')")
    int insertOrderForTest();

    @Select("SELECT COUNT(*) FROM pay_log WHERE order_id = #{orderId}")
    int countLogs(long orderId);
}
