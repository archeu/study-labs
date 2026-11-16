# 限流算法：令牌桶、漏桶、滑动窗口

对应页面：[限流算法：令牌桶、漏桶、滑动窗口](https://study.ainiya.de/arch/rate-limit-algorithms.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.arch.rate_limit_algorithms` 包下的 main。
- 命令行：`cd java && mvn -q -pl arch/rate-limit-algorithms compile exec:java -Dexec.mainClass=labs.arch.rate_limit_algorithms.<类名>`

## 预期输出

2 核 ARM 虚拟机，JDK 17，Guava 33.5.0-jre（`RateLimitLab`）：

```
A. 窗口边界（900~1099 ms 共 200 个，限流 100/秒）：固定窗口 200 · 滑动 2 格 100 · 滑动 10 格 100 · 令牌桶 119
A2. 两波（400 ms、1000 ms 各 100 个）：固定窗口 200 · 滑动 2 格 200 · 滑动 10 格 100 · 令牌桶 169
B. 开抢（1000/秒，3 秒）每秒放行：固定窗口 100/100/100 · 令牌桶 299/100/100 · 整秒补 200/100/100 · 漏桶 100/100/100（最长排队 500 ms）
C. Guava：新建后连续 tryAcquire 300 次成功 1 次；空闲 1.5 秒后成功 101 次；acquire(50) 不等，下一个 acquire(1) 等 0.5 秒
```

- `Limiters`：固定窗口、滑动窗口（分桶）、令牌桶（连续补）、令牌桶（按整秒补，照抄 Spring Cloud Gateway 的 Lua）、漏桶排队。
- `RateLimitLab`：模拟时钟喂同一份流量，再用 Guava 跑真实时钟。

## 练习

先猜结果，再运行。

1. `TokenBucket` 不是线程安全的：200 个线程并发调用，数放行数，再用 `synchronized` 或 CAS 修好。
2. 在实验 B 前面再串一层按用户的限流（每人 1/秒），1000 个用户随机发请求。
3. 被拒的请求 1 秒后重试一次，重跑实验 B，看第 2 秒的到达量。
4. 在本机 Redis 里执行 Gateway 的 `request_rate_limiter.lua`，结果应和 B 的“整秒补”一行一样。
