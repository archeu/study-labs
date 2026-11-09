# HashMap 的亲戚们：LinkedHashMap、WeakHashMap、ThreadLocalMap

对应页面：[HashMap 的亲戚们](https://archeu.github.io/study-notes/javase/hashmap-relatives.html)（HashMap 页的下一圈）

`Demo` 分 6 段：LinkedHashMap 两种顺序、LRU 商品缓存、WeakHashMap 挂订单附加信息和两个坑、
ThreadLocalMap 的格子（反射）、线程池里忘了 remove 导致串号、线程池里挂大对象。

## 怎么跑

JDK 17。第 4 段反射读 `Thread.threadLocals`，要加 `--add-opens java.base/java.lang=ALL-UNNAMED`。

```bash
cd java
mvn -q -pl javase/hashmap-relatives compile
java --add-opens java.base/java.lang=ALL-UNNAMED -Xmx256m \
  -cp javase/hashmap-relatives/target/classes labs.javase.hashmap_relatives.Demo
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20（节选）：

```
访问顺序（accessOrder）：[66, 99, 88]
LRU 容量 3，查 88 66 99 88 12 66 7：依次挤掉 66、99、88，最后 [12, 66, 7]
WeakHashMap：放 10000 个、留一半强引用，GC 后 size() = 5000
坑：value 就是 key，GC 后 size() = 10000；字面量 key 还在 true，new String 的 false
请求 B（匿名用户）在 pool-1-thread-1 上，读到当前用户 = 1024
4 个线程各放 20 MB，任务结束 GC 后多占约 84 MB；remove 后 0 MB
```

## 练习

先猜结果，再运行。

1. 把第 2 段的 accessOrder 改成 false，最后剩哪三个？
2. 不用 LinkedHashMap，用 HashMap + 自己写的双向链表实现同样的 LRU。
3. 把 `selfRef.put(o, o)` 改成 value 不引用 key，GC 后 size 是多少？
4. 在全景的订单服务里加一个设置当前用户的 Filter，故意不 remove，复现串号。
