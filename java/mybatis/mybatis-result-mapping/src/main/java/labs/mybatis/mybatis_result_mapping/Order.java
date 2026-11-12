package labs.mybatis.mybatis_result_mapping;

import java.util.List;

/** 订单。items 由结果映射装进来：N+1、懒加载或 JOIN 折叠。 */
public class Order {
    private Long id;
    private Long userId;
    private String status;
    private Long amount;
    private List<Item> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    /** 故意不碰 items：toString 照样会触发懒加载，因为它在 lazyLoadTriggerMethods 里。 */
    @Override public String toString() { return "Order#" + id + "(" + status + ", " + amount + ")"; }
}
