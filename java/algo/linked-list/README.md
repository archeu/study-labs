# 链表与空间换时间：多存几根指针，撤单就是 O(1)

对应页面：[链表与空间换时间：多存几根指针，撤单就是 O(1)](https://study.ainiya.de/algo/linked-list.html)（算法基础第 4 课）

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.linked_list` 包下的 main。
- `OrderQueue`（手推那一段，输出确定）：`cd java && mvn -q -pl algo/linked-list compile exec:java -Dexec.mainClass=labs.algo.linked_list.OrderQueue`
- `SpaceCost`（JOL 数字节 + 递归反转溢栈）：同上，换成 `SpaceCost`。JOL 打的 Serviceability Agent 警告可以不管。
- `CancelBench`（计时粗测，别在 Maven 进程里跑，直接起 JVM）：编译后 `java -Xms1g -Xmx1g -cp algo/linked-list/target/classes labs.algo.linked_list.CancelBench`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译，压缩指针开着（默认）。

`OrderQueue`：

```
空队列              size=0  head ⇄ tail
挂 5 张单 #101～#105 size=5  head ⇄ #101 ⇄ #102 ⇄ #103 ⇄ #104 ⇄ #105 ⇄ tail
撤 #103（中间）       size=4  head ⇄ #101 ⇄ #102 ⇄ #104 ⇄ #105 ⇄ tail
成交 #101（队头）      size=3  head ⇄ #102 ⇄ #104 ⇄ #105 ⇄ tail
撤 #105（队尾）       size=2  head ⇄ #102 ⇄ #104 ⇄ tail
挂 #106           size=3  head ⇄ #102 ⇄ #104 ⇄ #106 ⇄ tail
全部成交             size=0  head ⇄ tail
```

`SpaceCost`（确定性）：

```
== 1 十万张单，各占多少（含 Long 对象，JOL 整图统计）==
  ArrayList<Long>             2,826,880 字节   每张   28.3 字节
  ArrayDeque<Long>            2,834,696 字节   每张   28.3 字节
  LinkedList<Long>            4,800,032 字节   每张   48.0 字节
  OrderQueue（双链表+索引）     9,848,736 字节   每张   98.5 字节
== 2 反转单链表：循环 vs 递归 ==
  n =    10,000   循环：成功   递归：成功
  n =   100,000   循环：成功   递归：StackOverflowError
```

`CancelBench`（粗测，热身 2 次取 3 次最快；跑了两遍，前两列比值都在 ×3.9～×4.8）：

```
== 1 撤掉 n 张单（随机顺序），总耗时 ms ==
      n   ArrayList.remove(o)   LinkedList.remove(o)   OrderQueue.cancel(id)
  10000         30.1 ×4.1           61.4 ×4.0            2.36
  40000        545.4 ×4.6         1104.4 ×4.2            5.07
== 2 把 LinkedList 从头到尾读一遍，ms ==
  40000        689.3 ×4.2        0.308
```

## 练习

先猜结果，再运行。

1. 给 `OrderQueue` 加 `touch(id)`：把这张单挪到队尾。这就是 LRU 缓存的核心，和 `new LinkedHashMap<>(16, 0.75f, true)` 做的事一样。
2. 去掉两个哨兵重写 `add` 和 `unlink`，数一数多了几个 `if`，再用 `checkInvariants()` 跑一遍手推那段。
3. id 是连续整数时，把 `HashMap<Long, Node>` 换成 `Node[]`（下标 = id − 起始号），再跑 `SpaceCost`：每张单省多少字节？
4. 用 `-Xss8m` 跑 `SpaceCost`：10 万个节点的递归反转还会溢出吗？100 万呢？
