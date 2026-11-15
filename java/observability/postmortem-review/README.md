# 对事不对人：故障复盘，时间线、根因、改进项

对应页面：[对事不对人：故障复盘，时间线、根因、改进项](https://archeu.github.io/study-notes/observability/postmortem-review.html)（J13 全景第 11 站）

复盘第一步是拼时间线，而且要从原始记录拼，不凭记忆。`TimelineBuilder` 读 [Prometheus 那页](../prometheus-alerting/) 的 `run.sh` 打出的原始事件（每 10 秒的成功和失败数、告警规则状态变化、webhook 通知、注入时刻），排成一张 Markdown 时间线，再算出“发现”“恢复”各用了多久。

- `sample/prometheus-alerting-events.txt`：2026-11-15 那次本机实测的原始事件，原样保留。
- `postmortem-example.md`：全景那次故障的复盘文档，按页面 ⑤ 的骨架写。

## 怎么跑

JDK 17，没有第三方依赖。

```bash
cd java/observability/postmortem-review
mvn -q compile
java -cp target/classes labs.observability.postmortem_review.TimelineBuilder                 # 读 sample 里的实测事件
java -cp target/classes labs.observability.postmortem_review.TimelineBuilder <你自己的事件文件>
```

事件文件每行一条：`HH:MM[:SS] <类别> <内容>`，类别是 `T0`（注入或变更）、`load`（`200=… 5xx=…`）、`rule`、`webhook`。

## 预期输出

```
| 时间 | 类别 | 发生了什么 |
|---|---|---|
| 22:56:04 | 变更 / 故障 | 注入：order-3 上 10 个线程各攥一条连接 150 秒 |
| 22:56:10 | 用户影响 | 这 10 秒里第一次出现 5xx：11 次（共 108 次） |
| 22:56:20 | 告警规则 | HikariPendingHigh inactive -> pending |
| 22:56:35 | 告警规则 | OrderCreateErrorRateHigh inactive -> pending |
| 22:56:50 | 告警规则 | HikariPendingHigh pending -> firing |
| 22:57:19 | 通知到人 | [firing HikariPendingHigh -] |
| 22:57:35 | 告警规则 | OrderCreateErrorRateHigh pending -> firing |
| 22:58:04 | 通知到人 | [firing OrderCreateErrorRateHigh -] |
| 22:58:50 | 告警规则 | HikariPendingHigh firing -> inactive |
| 22:58:50 | 用户影响 | 这 10 秒里 5xx 归零，恢复 |
| 22:59:19 | 通知到人 | [resolved HikariPendingHigh -] |
| 22:59:35 | 告警规则 | OrderCreateErrorRateHigh firing -> inactive |
| 23:00:04 | 通知到人 | [resolved OrderCreateErrorRateHigh -] |

从时间线算出来的数（以注入时刻 22:56:04 为起点）：
- 用户第一次受影响（所在 10 秒窗口的末尾）：+6 秒（22:56:10）
- 第一条告警规则进入 pending：+16 秒（22:56:20）
- 第一条通知到人（发现）：+75 秒（22:57:19）
- 5xx 归零（恢复）：+166 秒（22:58:50）
通知到恢复：91 秒。这次没有人处理，是“对账”自己结束了；真实事故里这一段是排查加止血。
```

## 练习

1. 用 Prometheus 实验把错误率告警的 `for` 改回 5m 重跑，把新的事件喂进来，“发现”变成多少？
2. 给每一行加一列“出处”（load 来自压测程序，rule 来自 Prometheus API，webhook 来自假手机）。
3. 按页面 ⑥ 练习 2，把一份追责式结论改写成对事不对人，再把改进项放进页面 ④ 的评审器。
