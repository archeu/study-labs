# 长任务架构：队列、工作者、状态存储、进度推送

对应页面：[长任务架构：队列、工作者、状态存储、进度推送](https://study.ainiya.de/agent/long-task-queue.html)

全景“大促夜里 1000 个告警同时涌进来”第 1～3 站的最小实现：

| 文件 | 做什么 |
|---|---|
| `ingress.py` | FastAPI 接入服务：Bearer token 认租户，按指纹去重，按“租户 + 服务 + 告警名 + 5 分钟窗口”合并成事件，写进 `oncall:p0/p1/p2` 三条 Redis Stream，马上回 202；`GET /incidents/{id}/events` 用 SSE 推进度，支持 `Last-Event-ID` |
| `worker.py` | 消费组 worker：先 `XAUTOCLAIM` 捡别人崩掉的任务，再按优先级 `XREADGROUP`；每轮存 SQLite 检查点、写进度流；做完才 `XACK`；投递超过 3 次进死信流；`--heartbeat` 用 `XCLAIM … JUSTID` 续租 |
| `common.py` | 假模型（6 轮固定剧本，每轮睡 `MODEL_SEC` 秒）、假工具、建工单的幂等键 |
| `demo.py` | 把 5 个实验连起来跑 |

没有真模型，也不需要 API key。

## 怎么跑

```bash
# 一个 Redis（7.0 以上，XAUTOCLAIM 的三元返回值要 7.0）
sudo docker run -d --name s10-redis -p 127.0.0.1:63710:6379 redis:8.12.0-alpine

cd agent && uv sync
REDIS_URL=redis://127.0.0.1:63710/0 uv run python long-task-queue/demo.py   # 约 70 秒
uv run pytest long-task-queue/
```

## 预期输出

2026-11-28，2 核 ARM，Python 3.13.18，redis-py 8.2.0，FastAPI 0.165.0，Redis 8.12.0：

```
== 1. 1000 条告警打进接入服务
告警 1000 条 → 重发去掉 120，合并 793，新事件 87；队列 p0/p1/p2 = [9, 31, 47]
webhook 请求 268 个，202 耗时 p50 2.8 ms，p99 6.7 ms
== 2. 4 个 worker 做完 87 个调查（每轮模型 0.15 秒）
做完 87 个，用时 26.6 秒；最后一个 P0 是第 9 个做完的，第一个 P2 是第 41 个
各优先级平均完成时刻：P0 2.5 秒，P1 8.3 秒，P2 19.9 秒
== 3. worker A 在第 5 轮（建工单之后、写检查点之前）被杀，B 接手
检查点有、工具幂等：模型调用 7 次，工单 1 张，做完用时 4.3 秒，待确认剩 0
检查点有、工具不幂等：模型调用 7 次，工单 2 张
检查点无、工具幂等：模型调用 11 次，工单 1 张
检查点无、工具不幂等：模型调用 11 次，工单 2 张
== 4. 一轮模型要 1.5 秒，min-idle 只设 1 秒
没心跳：模型调用 12 次，经手 worker 2 个，“做完”记了 2 次
有心跳：模型调用 6 次，经手 worker 1 个，“做完”记了 1 次
== 5. SSE 读到第 3 条断开，带 Last-Event-ID 重连
重连后补上 ['第 4/6 轮：search_notes', '第 5/6 轮：create_ticket', '报告已出']；一共 6 条，重复 0 条
```

耗时数字会随机器变，次数和张数不会。

## 练习

先猜结果，再运行。

1. 把 `worker.py` 里的 `XACK` 挪到 `investigate()` 开头（先确认再干活），再跑实验 3：B 还能接手吗？
2. 把检查点改成“工具执行前先记一笔‘要调 create_ticket’，执行后再记结果”，重放时看到“要调但没结果”就先查工单系统再决定：不靠幂等键，能不能也只建一张？
3. 让第 3 个事件的假工具每次都抛异常，看它第几次投递后进了 `oncall:dead`。
4. 给 `ingress.py` 加一条：同一个租户 1 分钟内超过 30 个新事件就只记不调查（降级），P0 例外。
