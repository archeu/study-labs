package labs.java.pano_j01_order_object_life;

/** 第 7 站：库存不够。继承 RuntimeException，@Transactional 默认只对它（和 Error）回滚。 */
public class OutOfStockException extends RuntimeException {
    public OutOfStockException(long skuId, int qty) {
        super("库存不足：sku " + skuId + " 要 " + qty + " 件");
    }
}
