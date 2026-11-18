# 栈、队列和环形数组：一块数组首尾相接，两头都是 O(1)

对应页面：[栈、队列和环形数组：一块数组首尾相接，两头都是 O(1)](https://study.ainiya.de/algo/stack-queue-ring.html)（算法基础第 5 课）

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.stack_queue_ring` 包下的 main。
- `OrderRing`（手推那一段，输出确定）：`cd java && mvn -q -pl algo/stack-queue-ring compile exec:java -Dexec.mainClass=labs.algo.stack_queue_ring.OrderRing`
- `StackDemo`（括号检查 + `Stack` 和 `ArrayDeque` 遍历顺序）：同上，换类名。
- `QueueBench`（计时粗测，别在 Maven 进程里跑，直接起 JVM）：编译后 `java -Xms1g -Xmx1g -cp algo/stack-queue-ring/target/classes labs.algo.stack_queue_ring.QueueBench`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

`OrderRing`（容量 4，槽 = 序号 & 3）：

```
空队列                    [ ·    ·    ·    ·  ]  head=0→槽0  tail=0→槽0  size=0
入 #101 #102 #103       [#101 #102 #103  ·  ]  head=0→槽0  tail=3→槽3  size=3
出 2 张（#101 #102）       [ ·    ·   #103  ·  ]  head=2→槽2  tail=3→槽3  size=1
入 #104 #105（绕回槽 0）     [#105  ·   #103 #104]  head=2→槽2  tail=5→槽1  size=3
入 #106                 [#105 #106 #103 #104]  head=2→槽2  tail=6→槽2  size=4
入 #107 → 满了，拒绝         [#105 #106 #103 #104]  head=2→槽2  tail=6→槽2  size=4
全部出队：#103 #104 #105 #106 [ ·    ·    ·    ·  ]  head=6→槽2  tail=6→槽2  size=0
```

`StackDemo`：

```
== 1 括号检查（栈）==
  price > 100 && (qty < 10 || (side == BUY && acct in [A, B]))
  → 配上了
  price > 100 && (qty < 10 || side == BUY
                 ^ 下标 15 出错
  (qty < 10 ] || side == SELL)
            ^ 下标 10 出错
== 2 同样 push 1 2 3，遍历出来的顺序 ==
  Stack       for-each：[1, 2, 3]   peek()=3
  ArrayDeque  for-each：[3, 2, 1]   peek()=3
```

`QueueBench`（跑两遍，n ≥ 1 万的列一致；n = 1,000 那列还在 JIT 预热，不看）：

```
                          n=1,000           n=10,000          n=100,000
ArrayList          131.0 ns    0 B     953.6 ns    0 B   13128.3 ns    0 B
LinkedList          47.7 ns   24 B      18.5 ns   24 B      16.6 ns   24 B
ArrayDeque          25.1 ns    0 B      11.7 ns    0 B      11.6 ns    0 B
OrderRing           17.9 ns    0 B      10.0 ns    0 B       9.2 ns    0 B
```

读法：`ArrayList.remove(0)` 跟着 n 涨（O(n)）；`LinkedList` 每对操作新分配 24 B（一个节点）；两个环形数组不涨、零分配。

## 练习

先猜结果，再运行。

1. 给 `OrderRing` 加 `offerOverwrite`：满了先 `head++` 再放。容量 4 依次放 #101～#105，出队应是 #102 #103 #104 #105。
2. 把序号改成回绕的 int 下标，用“留一个空格”判满。容量 4 只能放 3 张，“入 #106”那一行会被拒。
3. 满了改成扩到 2 倍：先故意写成 `Arrays.copyOf`，看出队顺序怎么乱；再把 head 那一段挪到新数组末尾修好。
4. 用两个 `ArrayDeque` 拼一个队列，加进 `QueueBench`，看它比 `ArrayDeque` 慢多少、跟不跟 n 涨。
