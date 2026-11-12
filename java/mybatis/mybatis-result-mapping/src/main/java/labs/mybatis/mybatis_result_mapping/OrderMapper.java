package labs.mybatis.mybatis_result_mapping;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.session.ResultHandler;

@Mapper
public interface OrderMapper {
    /** 嵌套查询，立即加载：1 条查订单 + 每笔订单 1 条查明细。 */
    List<Order> listOrdersNested();
    /** 嵌套查询，懒加载：先只查订单，碰到 items 才去查。 */
    List<Order> listOrdersLazy();
    /** 一条 JOIN，靠 &lt;id&gt; 把多行折叠回一个订单。 */
    List<Order> listOrdersJoin();
    /** 同一条 JOIN，但明细的 resultMap 只写了 &lt;id&gt;，其余列指望自动映射。 */
    List<Order> listOrdersJoinAuto();
    /** JOIN 之后直接 LIMIT：限制的是行数，不是订单数。 */
    List<Order> listOrdersJoinLimit(@Param("rows") int rows);
    /** 先在子查询里分订单的页，再 JOIN 明细。 */
    List<Order> listOrdersPageThenJoin(@Param("size") int size);
    /** 嵌套结果映射配自定义 ResultHandler（没写 resultOrdered）。 */
    void scanOrdersJoin(ResultHandler<Order> handler);
    List<Item> listItems(long orderId);

    List<PayFlow> listFlows();
    /** 按页取流水：OFFSET + LIMIT。 */
    List<PayFlow> listFlowsPage(@Param("offset") int offset, @Param("size") int size);
    Cursor<PayFlow> cursorFlows();
    void scanFlows(ResultHandler<PayFlow> handler);
}
