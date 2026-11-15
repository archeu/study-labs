# 秒杀与抢券：削峰、预扣、异步化

对应页面：[秒杀与抢券：削峰、预扣、异步化](https://archeu.github.io/study-notes/arch/seckill-coupon.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.arch.seckill_coupon` 包下的 main。
- 一键：`DOCKER="sudo docker" ./run.sh`（起 `redis:8.10.3-alpine` 容器，端口 63799；`./run.sh clean` 删容器）
- 命令行：`cd java && mvn -q -pl arch/seckill-coupon compile exec:java -Dexec.mainClass=labs.arch.seckill_coupon.<类名>`

## 预期输出

2 核 ARM 虚拟机，JDK 17，Lettuce 6.8.2，Redis 8.10.3（1、2 段的具体数字每次会变）：

```
1. naive  GET → DECR → SADD：回“抢到了” 200 次（券 100 张），一人多张 18 人，库存 -100
2. lua    一段脚本做完：     回“抢到了” 100 次，一人多张 0 人，库存 0
3. flag 关 20000 个请求，Redis 被调用 20000 次；flag 开：137 次
4. async  投递 200 条（每条两次）：落库 100 行，撞唯一键 100 次；切主回涨 5 张，条件更新挡下 5 人，最终 100 张
```

第 4 段的“库”是内存里模拟的条件更新和唯一键，不是真 MySQL。

## 练习

先猜结果，再运行。

1. naive 改成直接 `DECR`，返回值 < 0 就 `INCR` 回去：还超发吗？一人多张还在吗？
2. 第 4 段换成真 MySQL（`mysql:8.4` 容器），4 个消费线程落库，量每秒能写多少。
3. 发消息前随机让 1% 的请求“崩掉”，写对账程序找出这些人并补发。
4. 售罄后把库存 `SET` 回 20，看有多少实例还在回“已抢完”；给标记加 5 秒过期再跑。
