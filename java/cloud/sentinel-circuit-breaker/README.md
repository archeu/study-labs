# inventory-2 慢了，下单怎么还能降级：限流、熔断、降级与 Sentinel

对应页面：[inventory-2 慢了，下单怎么还能降级：限流、熔断、降级与 Sentinel](https://study.ainiya.de/cloud/sentinel-circuit-breaker.html)（J10 全景第 3、8、9、10、11 站）

## 里面有什么

- `inventory.InventoryStub`：库存服务替身（返回端口、`/chaos/slow` 变慢开关、actuator 能 DOWN/UP）。
- `order.OrderApp` + `InventoryClient`：OpenFeign 调库存，`feign.sentinel.enabled=true` 让每次调用外面包一层 Sentinel，`fallback` 是降级逻辑。启动时加载全景第 10 站那条慢调用比例规则，并注册状态变化监听，打印熔断器每次换状态的时刻。`/demo/run` 用 20 个线程不停地调，按秒统计成功（端口）、降级（FALLBACK）、平均耗时。
- `run.sh`：A 有熔断（第 5 秒 inventory-2 变慢 3 s，第 30 秒把它 DOWN）；B 没有熔断规则，只有 800 ms 读超时。

版本：Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring Cloud Alibaba 2025.0.0.0（Sentinel 1.8.9）。Nacos 用 `../nacos-registry-config` 的容器。

## 怎么跑

```bash
cd java && mvn -q -pl cloud/sentinel-circuit-breaker package -DskipTests
cd cloud/sentinel-circuit-breaker
DOCKER="sudo docker" ./run.sh up    # 已经在跑就跳过
./run.sh demo                       # 约 3 分钟
DOCKER="sudo docker" ./run.sh down all
```

## 预期输出

2 核 ARM 虚拟机，JDK 17：

```
== A 有熔断：慢调用 > 500 ms 占比 > 40% → 熔断 10 s；第 5 秒 inventory-2 变慢 3 s，第 30 秒把它 DOWN
秒   完成数  平均耗时  结果（端口=成功，FALLBACK=降级）
00     239      65 ms  {18081=118, 18082=121}
01     270      55 ms  {18081=137, 18082=133}
02     335      44 ms  {18081=168, 18082=167}
03     365      38 ms  {18081=187, 18082=178}
04     478      28 ms  {18081=233, 18082=245}
05     118     149 ms  {18081=54, 18082=30, FALLBACK=34}
06    1676       2 ms  {FALLBACK=1676}
07    1977       0 ms  {FALLBACK=1977}
08    1969       0 ms  {FALLBACK=1969}
09    1971       0 ms  {FALLBACK=1971}
10    1978       0 ms  {FALLBACK=1978}
11    1974       0 ms  {FALLBACK=1974}
12    1980       0 ms  {FALLBACK=1980}
13    1980       0 ms  {FALLBACK=1980}
14    1980       0 ms  {FALLBACK=1980}
15    1872       0 ms  {18081=14, FALLBACK=1858}
16      46     357 ms  {18081=26, FALLBACK=20}
17     727      22 ms  {18081=1, FALLBACK=726}
18    1945       0 ms  {FALLBACK=1945}
19    1979       0 ms  {FALLBACK=1979}
20    1981       0 ms  {FALLBACK=1981}
21    1981       0 ms  {FALLBACK=1981}
22    1982       0 ms  {FALLBACK=1982}
23    1982       0 ms  {FALLBACK=1982}
24    1979       0 ms  {FALLBACK=1979}
25    1995       0 ms  {FALLBACK=1995}
26    1972       0 ms  {FALLBACK=1972}
27    1172       0 ms  {18081=20, FALLBACK=1152}
28    1107      14 ms  {18081=1, FALLBACK=1106}
29    1964       0 ms  {FALLBACK=1964}
30    1976       0 ms  {FALLBACK=1976}
31    1976       0 ms  {FALLBACK=1976}
32    1983       0 ms  {FALLBACK=1983}
33    1983       0 ms  {FALLBACK=1983}
34    1981       0 ms  {FALLBACK=1981}
35    1982       0 ms  {FALLBACK=1982}
36    1980       0 ms  {FALLBACK=1980}
37    1981       0 ms  {FALLBACK=1981}
38    1240       5 ms  {18081=441, FALLBACK=799}
39     747      14 ms  {18081=747}
40     794      13 ms  {18081=794}
41     769      13 ms  {18081=769}
42    1005       8 ms  {18081=1005}
43     985       8 ms  {18081=985}
44    1036       8 ms  {18081=1036}
事件：
    5.2 s ── inventory-2 每次 3 s
    5.9 s 熔断器 CLOSED → OPEN（慢调用占比 100%）
   15.9 s 熔断器 OPEN → HALF_OPEN
   15.9 s 熔断器 HALF_OPEN → CLOSED
   17.6 s 熔断器 CLOSED → OPEN（慢调用占比 43%）
   27.6 s 熔断器 OPEN → HALF_OPEN
   27.6 s 熔断器 HALF_OPEN → CLOSED
   28.4 s 熔断器 CLOSED → OPEN（慢调用占比 100%）
   30.3 s ── 运维把 inventory-2 DOWN（LoadBalancer 缓存 ttl 2 s）
   38.4 s 熔断器 OPEN → HALF_OPEN
   38.4 s 熔断器 HALF_OPEN → CLOSED
== B 没熔断：只有 800 ms 读超时；第 5 秒 inventory-2 变慢 3 s
秒   完成数  平均耗时  结果（端口=成功，FALLBACK=降级）
00     330      43 ms  {18081=166, 18082=164}
01     579      20 ms  {18081=289, 18082=290}
02     557      20 ms  {18081=278, 18082=279}
03     684      16 ms  {18081=341, 18082=343}
04     715      14 ms  {18081=357, 18082=358}
05     120     144 ms  {18081=71, 18082=29, FALLBACK=20}
06      41     394 ms  {18081=21, FALLBACK=20}
07      40     403 ms  {18081=20, FALLBACK=20}
08      40     403 ms  {18081=20, FALLBACK=20}
09      45     411 ms  {18081=22, FALLBACK=23}
10      75     397 ms  {18081=38, FALLBACK=37}
11      40     402 ms  {18081=20, FALLBACK=20}
12      40     405 ms  {18081=20, FALLBACK=20}
13      40     402 ms  {18081=20, FALLBACK=20}
14      54     416 ms  {18081=26, FALLBACK=28}
15      66     389 ms  {18081=34, FALLBACK=32}
16      40     401 ms  {18081=20, FALLBACK=20}
17      40     401 ms  {18081=20, FALLBACK=20}
18      40     401 ms  {18081=20, FALLBACK=20}
19      84     496 ms  {18081=32, FALLBACK=52}
事件：
    5.1 s ── inventory-2 每次 3 s
```

- B 里也有 FALLBACK：打开 `feign.sentinel.enabled` 以后，调用抛异常（这里是 800 ms 读超时）也会走 fallback，和有没有熔断规则无关。
- A 里半开探测成功一次就关上（15.9 s、27.6 s、38.4 s），可 inventory-2 还在列表里，1～2 秒后又被慢调用拖回 OPEN；直到 30.3 s 把它 DOWN，38.4 s 那次关上以后才稳定。

## 练习

先猜结果，再运行。

1. 把熔断规则从慢调用比例换成异常比例（`DEGRADE_GRADE_EXCEPTION_RATIO`，0.4），A 的时间线会怎样？再把读超时删掉（默认 60 s）呢？
2. 把 `timeWindow` 从 10 改成 3：开关会更频繁还是更少？下单成功的总数变多还是变少？
3. 用 Resilience4j（Spring Cloud CircuitBreaker）替换 Sentinel，半开时放 10 个请求：A 里那种“关上又打开”还会出现吗？
4. 给 `reserve` 加一条流控规则：QPS 500，超出直接拒绝。看 B 的前 5 秒发生了什么。
