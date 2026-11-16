# 从一个数到一通电话：Prometheus 加 Grafana，指标和告警怎么设

对应页面：[从一个数到一通电话：Prometheus 加 Grafana，指标和告警怎么设](https://study.ainiya.de/observability/prometheus-alerting.html)（J13 全景第 2、3、5 站）

把全景“凌晨三点”那次故障在本机重演一遍，量出从开始出错到“电话”响到底要多久，每一段对应哪个参数。

| 组件 | 端口 | 说明 |
|---|---|---|
| order-svc ×3（`OrderApp`） | 18091～18093 | Spring Boot 3.5.16，HikariCP 10 条连接、拿连接最多等 3 秒；`/admin/hold` 模拟对账线程攥着连接 |
| Prometheus 3.16.0 | 19090 | `conf/prometheus.yml`：抓取、规则都是 15 秒；`conf/rules.yml`：两条告警 + 一条录制规则 |
| Alertmanager 0.35.0 | 19093 | `conf/alertmanager.yml`：group_wait 30s，group_interval 缩成 1m（默认 5m） |
| 假手机（`WebhookReceiver`） | 19095 | 收到通知就打一行 |
| `LoadGen` | — | 每秒 12 单，平均打到三个实例 |
| `AlertWatcher` | — | 每秒问一次 Prometheus，告警状态一变就打一行 |

全景里错误率告警的 `for` 是 5 分钟，实验缩成 1 分钟省时间；另加一条复盘改进项里的“等连接告警”（`hikaricp_connections_pending > 0`，for 30s）做对比。

## 怎么跑

JDK 17，aarch64（x86 机器设 `ARCH=linux-amd64`）。第一次运行会把 Prometheus 和 Alertmanager 下载到 `data/`（约 140 MB，已被 .gitignore 挡住）。

```bash
cd java/observability/prometheus-alerting
./run.sh     # 约 6 分钟：就绪 → 45 秒基线 → T0 注入 → T0+90s 查询 → 再等 200 秒 → 打印按时间排序的事件
```

跑的过程中可以打开 http://localhost:19090 （Prometheus 页面，Alerts 页能看到 pending / firing）和 http://localhost:19093 （Alertmanager）。

## 预期输出

本机 2026-11-15，2 核 ARM，JDK 17。时间会因为抓取和规则计算的相位不同而前后差十几秒，页面上的计算器给出了范围。

```
22:56:04 T0 注入：order-3 上 10 个线程各攥一条连接 150 秒
22:56:10 load  last 10s: 200=97 5xx=11
22:56:20 rule  HikariPendingHigh  inactive -> pending
22:56:20 load  last 10s: 200=80 5xx=40
22:56:35 rule  OrderCreateErrorRateHigh  inactive -> pending
22:56:50 rule  HikariPendingHigh  pending -> firing
22:57:19 webhook  [firing HikariPendingHigh -]
22:57:35 rule  OrderCreateErrorRateHigh  pending -> firing
22:58:04 webhook  [firing OrderCreateErrorRateHigh -]
22:58:40 load  last 10s: 200=115 5xx=17
22:58:50 rule  HikariPendingHigh  firing -> inactive
22:59:19 webhook  [resolved HikariPendingHigh -]
22:59:35 rule  OrderCreateErrorRateHigh  firing -> inactive
23:00:04 webhook  [resolved OrderCreateErrorRateHigh -]

T0+90s：每秒下单 order-1/2/3 各 4.0；错误率（录制规则）只有 order-3 = 1.0（order-1、2 没有这一行）；
合起来的 P99 3.2105 s；等连接 order-3 = 12；近 1 分钟拿连接超时 order-3 = 240
```

从开始出错（T0+3s）算：等连接告警 72 秒到手机，错误率告警 117 秒到手机。

## 练习

先猜结果，再运行。

1. 把 `rules.yml` 里的录制规则改成补零写法（页面 ⑥ 坑 2），order-1、2 会出现 0 吗？
2. 加一条 `up{job="order-svc"} == 0`（for 30s），实验中途 `kill` 掉 order-2，多久收到通知？先用页面上的计算器算一个范围。
3. 把错误率告警的 `for` 改回 5m，通知会晚多少？和计算器里“全景配置”对一下。
4. 写一条 1 小时 + 5 分钟双窗口的燃烧率告警（SLO 99.9%），和错误率那条比，谁先响？
