package labs.algo.red_black_tree;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 卖盘：价位 → 这一档的挂单队列（同价先来先成交）。撤单拿订单号找到它所在的档。
 * TreeMap 给的：最优价 firstKey、前 k 档按序遍历、按价删整档，都是 O(log n)。
 */
public class AskBook {
    static final class Order { final long id, price; long qty; Order(long id, long price, long qty) { this.id = id; this.price = price; this.qty = qty; } }

    final TreeMap<Long, ArrayDeque<Order>> levels = new TreeMap<>();   // 价格升序：第一个就是最优卖价
    final Map<Long, Order> byId = new HashMap<>();

    public void add(Order o) {
        levels.computeIfAbsent(o.price, k -> new ArrayDeque<>()).addLast(o);
        byId.put(o.id, o);
    }

    public boolean cancel(long id) {
        Order o = byId.remove(id);
        if (o == null) return false;
        ArrayDeque<Order> q = levels.get(o.price);
        q.remove(o);                                   // 档内线性删；档内单数通常很少
        if (q.isEmpty()) levels.remove(o.price);       // 整档空了就把价位从树里删掉，O(log n)
        return true;
    }

    public String top(int k) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, ArrayDeque<Order>> e : levels.entrySet()) {
            if (k-- == 0) break;
            long qty = 0; for (Order o : e.getValue()) qty += o.qty;
            sb.append(String.format("%.2f×%d  ", e.getKey() / 100.0, qty));
        }
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        AskBook b = new AskBook();
        b.add(new Order(1, 10100, 3)); b.add(new Order(2, 10050, 1)); b.add(new Order(3, 10200, 5));
        b.add(new Order(4, 10050, 2)); b.add(new Order(5, 10300, 1)); b.add(new Order(6, 10150, 4));
        System.out.println("卖盘前 5 档：" + b.top(5));
        System.out.println("最优卖价 " + b.levels.firstKey() + "，同价第一张单 #" + b.levels.firstEntry().getValue().peekFirst().id);
        b.cancel(2); b.cancel(4);
        System.out.println("撤掉 #2、#4 后：" + b.top(5));
        NavigableMap<Long, ArrayDeque<Order>> within = b.levels.headMap(10200L, true);
        System.out.println("买 102.00 能吃到的档：" + within.keySet() + "（headMap 是视图，不拷贝）");
        System.out.println("买盘用降序：new TreeMap<>(Comparator.reverseOrder())，或 levels.descendingMap()");
    }
}
