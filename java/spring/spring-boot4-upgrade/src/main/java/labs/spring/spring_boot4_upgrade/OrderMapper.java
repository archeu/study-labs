package labs.spring.spring_boot4_upgrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OrderMapper {
    @Update("update orders set status='PAID', pay_no=#{payNo}, paid_at=#{paidAt} where id=#{id} and user_id=#{userId} and amount_cents=#{amount}")
    int markPaid(long id, long userId, long amount, long payNo, LocalDateTime paidAt);
}
