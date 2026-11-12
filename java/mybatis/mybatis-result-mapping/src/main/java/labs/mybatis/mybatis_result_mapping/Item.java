package labs.mybatis.mybatis_result_mapping;

public class Item {
    private Long id;
    private Long skuId;
    private Integer qty;
    private Long price;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQty() { return qty; }
    public void setQty(Integer qty) { this.qty = qty; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }

    @Override public String toString() { return "Item#" + id + "(sku=" + skuId + ", qty=" + qty + ", price=" + price + ")"; }
}
