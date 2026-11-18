package labs.algo.heap_priority_queue;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * 用两个 PriorityQueue 做一个最小的订单簿：价格优先、时间优先。
 * 买单：价高的先成交（大顶堆）；同价按到达序号 seq 小的先。
 * 卖单：价低的先成交（小顶堆）；同价同样按 seq。
 * 价格用 long 存“分”（0.01 元），不用 double。
 */
public class OrderBook {
    static final class Order {
        final String id; final long price; final long seq; long qty;
        Order(String id, long price, long qty, long seq) { this.id = id; this.price = price; this.qty = qty; this.seq = seq; }
        @Override public String toString() { return id + "@" + yuan(price) + "×" + qty; }
    }

    static final Comparator<Order> BID_ORDER =
            Comparator.comparingLong((Order o) -> o.price).reversed()   // 价高的在前
                      .thenComparingLong(o -> o.seq);                   // 同价先来的在前
    static final Comparator<Order> ASK_ORDER =
            Comparator.comparingLong((Order o) -> o.price)
                      .thenComparingLong(o -> o.seq);

    final PriorityQueue<Order> bids = new PriorityQueue<>(BID_ORDER);
    final PriorityQueue<Order> asks = new PriorityQueue<>(ASK_ORDER);
    long seq;

    void buy(String id, long price, long qty)  { match(new Order(id, price, qty, ++seq), true); }
    void sell(String id, long price, long qty) { match(new Order(id, price, qty, ++seq), false); }

    private void match(Order in, boolean isBuy) {
        PriorityQueue<Order> opp = isBuy ? asks : bids;
        System.out.println((isBuy ? "买 " : "卖 ") + in);
        while (in.qty > 0 && !opp.isEmpty()) {
            Order best = opp.peek();                                   // O(1) 看最优价
            boolean cross = isBuy ? best.price <= in.price : best.price >= in.price;
            if (!cross) break;
            long q = Math.min(in.qty, best.qty);
            in.qty -= q; best.qty -= q;                                // 改的是数量，不是排序用的价格和 seq
            System.out.println("   成交 " + q + " 手 @" + yuan(best.price) + "  对手 " + best.id + (best.qty == 0 ? "（吃完）" : "（剩 " + best.qty + "）"));
            if (best.qty == 0) opp.poll();                             // O(log n) 取走
        }
        if (in.qty > 0) {
            (isBuy ? bids : asks).offer(in);                           // O(log n) 挂进簿
            System.out.println("   剩 " + in.qty + " 手挂进" + (isBuy ? "买" : "卖") + "簿");
        }
    }

    static String yuan(long cents) { return String.format("%d.%02d", cents / 100, cents % 100); }

    public static void main(String[] args) {
        OrderBook book = new OrderBook();
        book.buy("B1", 10050, 3);
        book.buy("B2", 10100, 2);
        book.buy("B3", 10100, 4);
        book.buy("B4",  9950, 5);
        book.sell("S1", 10200, 1);
        System.out.println("最优买价 " + book.bids.peek() + "   最优卖价 " + book.asks.peek());
        book.sell("S2", 10000, 7);
        System.out.println("最优买价 " + book.bids.peek() + "   最优卖价 " + book.asks.peek());
    }
}
