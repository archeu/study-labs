# HashMap：哈希、扩容、树化

对应页面：[HashMap：哈希、扩容、树化](https://archeu.github.io/study-notes/javase/hashmap-internals.html)
（全景：一个订单对象的一生 · 第 4、8 站；J01 过关题）

`Demo` 用反射（`Peek`）直接看 HashMap 内部的 `table`、`threshold` 和节点类型，分 6 段：
扩容时机、全景第 4 站的 88 和 344、一个桶从链表到红黑树、坏 hashCode 的查找耗时、全景第 8 站两个线程并发写、可变 key。

## 怎么跑

JDK 17。反射读 `java.util` 内部要加 `--add-opens`。

```bash
cd java
mvn -q -pl javase/hashmap-internals compile
java --add-opens java.base/java.util=ALL-UNNAMED \
  -cp javase/hashmap-internals/target/classes labs.javase.hashmap_internals.Demo
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20（节选）：

```
放第   1 个后：桶数组  16，阈值  12
放第  13 个后：桶数组  32，阈值  24
...
放第  97 个后：桶数组 256，阈值 192
hashCode=88，扰动后 88，桶数组 256，下标 88，桶里：1 个节点，Node
放进 344：下标 88（344 = 256 + 88），88 号的桶里：2 个节点，Node
商品加到 201 个：桶数组 512，88 的下标 88（1 个节点，Node），344 的下标 344（1 个节点，Node）
放第  9 个：桶数组  32，那个桶 9 个节点，Node
放第 10 个：桶数组  64，那个桶 10 个节点，Node
放第 11 个：桶数组  64，那个桶 11 个节点，TreeNode
全撞 + Comparable  查 20000 次：约 5～9 ms；全撞 + 不可比较：约 4000 ms
HashMap.put 两线程各 10 万：每次 size() 都不对，多数时候真丢数据
```

## 练习

先猜结果，再运行。

1. 过关题：在 `catalog.put(344L, …)` 处打断点，单步进 `HashMap.putVal`，对照页面④-2 的 9 步。
2. 把 catalog 改成 `new HashMap<>(134)`，放 100 个还会扩容吗？`new HashMap<>(100)` 呢？
3. 把 BadKey 放进 `new HashMap<>(64)`，第几个变成 TreeNode？
4. 第 5 段换成 ConcurrentHashMap 跑 3 次。
