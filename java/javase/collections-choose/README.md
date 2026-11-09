# 常用集合怎么选：ArrayList、LinkedList、Set、Queue

对应页面：[常用集合怎么选](https://archeu.github.io/study-notes/javase/collections-choose.html)
（全景：一个订单对象的一生 · 第 8、9 站）

- `OrderBook`：交易靶场的订单簿，五种找法五种集合（HashMap + List、HashSet、PriorityQueue、TreeMap、ArrayDeque），再演示四个坑。
- `Bench`：8 个场景各换 2～4 种集合粗测（不是 JMH，热身 5 轮、测 7 轮取中位数）。
- `Memory`：100 万个元素，每个元素集合本身多占几字节。

## 怎么跑

JDK 17。

```bash
cd java
mvn -q -pl javase/collections-choose compile
C=javase/collections-choose/target/classes
java -cp $C labs.javase.collections_choose.OrderBook
java -Xms1g -Xmx1g -cp $C labs.javase.collections_choose.Bench    # 约 40 秒
java -Xms1g -Xmx1g -cp $C labs.javase.collections_choose.Memory
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20（Bench 节选，ms）：

```
按下标 get(i) 2 万次     ArrayList 0.202     LinkedList 151.318
头部插入 5 万            ArrayList.add(0,x) 333.592   LinkedList.addFirst 0.605   ArrayDeque.addFirst 0.706
去重 2 万个请求号        ArrayList.contains 249.064   HashSet 1.022   TreeSet 1.953
按用户查订单 1 万次      ArrayList 全表扫 1476.803    HashMap.get 0.649
按到期先后取出 2 万个    每次扫最小 511.837   PriorityQueue 3.996   TreeMap 9.582
```

Memory：ArrayList（预设容量）3.7 字节/元素，LinkedList 24.0，TreeSet 40.0，HashSet 41.5。

## 练习

先猜结果，再运行。

1. 把 OrderBook 里按价格比较的 TreeSet 改成 `comparing(price).thenComparing(skuId)`，size 变成几？
2. 用 LinkedHashMap（`accessOrder = true` + `removeEldestEntry`）加一个“最近查过的 100 个用户”缓存。
3. 用户付款后要撤回他的 CancelTask：`PriorityQueue.remove(obj)` 是 O(n)，换个思路（取出时检查订单状态）。
4. 把 cancelQueue 换成按 deadline 比较的 TreeSet，同一秒下两单会怎样？
