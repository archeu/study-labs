package labs.spring.spring_boot_bean_ledger;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper {
    record Order(long id, long userId, long amountCents, String status) {}

    @Select("SELECT id, user_id, amount_cents, status FROM orders WHERE id = #{id}")
    Order findById(long id);

    // 第 9 站真正发出去的那条 SQL；J06 全景从这里接着往下走
    @Update("UPDATE orders SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int changeStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to);
}
