# 改完价，详情页还卖 399：先改库还是先删缓存、延迟双删、订阅 binlog

对应页面：[改完价，详情页还卖 399：先改库还是先删缓存、延迟双删、订阅 binlog](https://archeu.github.io/study-notes/redis/cache-consistency.html)（Java 地图 J07 · [全景：一次商品详情查询](https://archeu.github.io/study-notes/java/pano-j07-product-detail-cache.html) 第 9、10 站）

交易靶场：运营把 88 号商品从 399.00 改成 359.00。`PriceChange` 里 4 个读线程每 1 ms 按 Cache-Aside 读一次（GET → 没命中查库 → SET 回 Redis，30 分钟过期），
写线程按不同写法改库、动缓存。写完 300 ms 后 Redis 里留着和数据库不一样的值，就记一次“卡住”。缓存值的格式是 `价格|v版本号`。

## 怎么跑

需要 docker（本机不在 docker 组里就用 `DOCKER="sudo docker" ./run.sh`）。

```bash
./run.sh            # 起 Redis 8.10.3 和 MySQL 8.4 两个容器，三组全跑（约 80 秒）
./run.sh forced     # 容器留着，只跑某一组（natural / forced / binlog / all）
./run.sh clean      # 删掉两个容器（连同数据卷）
```

| 组 | 做什么 |
|---|---|
| natural | 不做手脚，5 种写法各 20 轮：先删后改、先改后删（缓存热 / 空）、延迟双删、两个运营同时改价并 SET 新值 |
| forced | 把最坏的先后排出来：缓存刚过期，一个读线程查完库后停 P ms 才回填；包括延迟双删、墓碑 + Lua 带版本回填 |
| binlog | 用 mysql-binlog-connector-java 伪装成从库读 binlog，看到 `trade.product` 的 UPDATE 就 DEL；测提交到删除的延迟 |

## 预期输出（本机 2 核 ARM，JDK 17.0.21，`--release 8`，同机容器）

```
== natural：4 个读线程每 1 ms 读一次，不做任何手脚，每种 20 轮
   写法                                   缓存开始时   卡住轮数
   ① 先删缓存，再改库                         热            18 / 20   （写一次平均 8.2 ms）
   ② 先改库，再删缓存                         热             0 / 20   （写一次平均 5.6 ms）
   ② 先改库，再删缓存                         空             0 / 20   （写一次平均 5.5 ms）
   ③ 先删 → 改库 → 等 100 ms → 再删          热             0 / 20   （写一次平均 107.0 ms）
   ④ 先改库，再 SET 新值（两个运营同时改）            热             0 / 20   （写一次平均 16.6 ms）
== forced：缓存刚过期，一个读线程查完库后停 P ms 才回填（GC 停顿、慢序列化），写线程等它查完库再改价
   ② 先改库，再删缓存                             慢读回填后 Redis=399.00|v222  再读一次后 Redis=399.00|v222  DB=359.00|v223  卡住
   ③ 延迟双删，等 100 ms，读停 50 ms               慢读回填后 Redis=null         再读一次后 Redis=359.00|v225  DB=359.00|v225  一致
   ③ 延迟双删，等 100 ms，读停 150 ms              慢读回填后 Redis=399.00|v226  再读一次后 Redis=399.00|v226  DB=359.00|v227  卡住
   ⑤ 改库后留墓碑 + 带版本回填，读停 150 ms             慢读回填后 Redis=#|v229       再读一次后 Redis=359.00|v229  DB=359.00|v229  一致
   ④ 两个运营，A 改完库停 30 ms 才 SET              Redis=359.00|v231  DB=329.00|v232  卡住
== binlog：一个伪装成从库的客户端读 binlog，看到 trade.product 的 UPDATE 就 DEL product:<id>
   改完库没删缓存（模拟进程挂了），500 ms 后 Redis 里 product:88 已被 binlog 订阅者删掉
   提交返回 → binlog 订阅者删完：p50 0.7 ms，最大 1.8 ms（20 次）
   缓存刚过期 + 读停 50 ms 才回填：Redis=399.00|v275  DB=359.00|v276  卡住（binlog 的 DEL 早到了 40 多 ms，回填在它后面）
```

另一次完整运行：先删后改卡住 15 / 20，binlog p50 1.7 ms、最大 3.0 ms，其余相同。

## 练习

先猜结果，再运行。

1. 在 `updateDelete()` 里让 DEL 有 30% 概率抛异常（模拟超时），看 natural 卡住几轮；再加一个重试队列（失败的 key 放进 List，后台线程每 100 ms 重删）。
2. 加一个从库（照 database/mysql-replication-lag 的做法），读线程回填时查从库，看先改库再删还卡不卡；再改回主库，或者换成异步的延迟双删。
3. 去掉业务代码里的 DEL，只靠 binlog 订阅者删，再跑 natural；再用 `docker exec cc-mysql mysql -uroot -proot trade -e "UPDATE product SET price=300 WHERE id=88"` 直接改库，看缓存有没有被删。
4. 读线程加到 32 个，每次查完库随机停 0～20 ms，看“先改库再删”的卡住轮数怎么变。
