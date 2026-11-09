# 全景：一个订单对象的一生

对应页面：[全景：一个订单对象的一生](https://archeu.github.io/study-notes/java/pano-j01-order-object-life.html)

一个最小的 Spring Boot 3.5 订单服务（交易靶场 J01 骨架：商品、库存、订单三张表，H2 内存库代替 MySQL）。
每一站打点，日志里打印这一站看到的类名、HashMap 桶下标、泛型擦除、距请求进来的微秒数。

## 怎么跑

JDK 21。第 4 站要反射读 HashMap 内部，所以加 `--add-opens`（不加也能跑，那一行会提示被模块系统拦下）。

```bash
cd java
mvn -q -pl java/pano-j01-order-object-life compile exec:java \
  -Dexec.mainClass=labs.java.pano_j01_order_object_life.OrderApp
# 想看桶数组：MAVEN_OPTS="--add-opens java.base/java.util=ALL-UNNAMED" 再运行上面这条

curl -s -H 'Content-Type: application/json' \
  -d '{"userId":1024,"items":[{"skuId":88,"qty":2}],"coupon":"MINUS20"}' localhost:18080/orders
curl -s -H 'Content-Type: application/json' \
  -d '{"userId":1024,"items":[{"skuId":88,"qty":100}]}' localhost:18080/orders   # 409
curl -s localhost:18080/stats    # 集合和延迟队列里攒了多少
```

## 预期输出

本机 2 核 ARM、JDK 21.0.12、Spring Boot 3.5.16，预热后的一单：

```
[ 2]     901 us  Jackson 反序列化出 PlaceOrderCmd[userId=1024, items=[Item[skuId=88, qty=2]], coupon=MINUS20]，isRecord=true
[ 3]     923 us  注入的 orderService 实际是 OrderService$$SpringCGLIB$$0
[ 4]    1095 us  catalog.get(88L)：hashCode=88，扰动后 88，100 个商品，桶数组长 256，下标 88，这个桶里 1 个节点（Node）
[ 5]    1160 us  new Order(id=9728)；ArrayList<OrderLine>.class == ArrayList<String>.class：true；但字段签名仍是 java.util.List<OrderLine>
[ 6]    1240 us  Stream 求和 598.00，优惠 Minus[off=20] → 应付 578.00
[11]    2606 us  响应 200 已交给 Tomcat 写回连接
```

进程刚启动的第一单约 151 ms，第 2 站就占 67 ms。

## 练习

先猜结果，再运行。

1. 往 `data.sql` 加一个 id 为 344 的商品，`get(344L)` 落在哪个桶？和 88 同桶吗？商品加到 200 个以后呢？
2. 把 `OutOfStockException` 改成继承 `Exception`，一单买两种商品、第二种不够，第一种的库存回不回来？
3. 用两个线程并发给不同新用户下单几万次，看 `/stats` 里 `users` 对不对得上。
4. 连发几千单后 `jcmd <pid> GC.class_histogram | grep Order`，Order 还剩几个？为什么 PlaceOrderCmd 一个都不剩？
