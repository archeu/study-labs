# 停止条件：步数、预算、超时

对应页面：[停止条件：步数、预算、超时](https://study.ainiya.de/agent/agent-stop-conditions.html)

用照剧本出牌的假模型（`../pano-04-triage-agent/fake_model.py`）制造四种情况，看三道闸（步数、token 预算、墙钟时长）哪道先拦住。不需要模型服务，也不需要 key。

## 怎么跑

```bash
cd agent && uv sync
uv run python agent-stop-conditions/main.py              # 四个场景
uv run python agent-stop-conditions/main.py --no-limits  # 拆掉三道闸，只留 60 轮安全绳
```

## 预期输出

2026-11-24，2 核 ARM，Python 3.13（token 是假模型按字符数粗估的）：

```
上限：步数 15，token 30000，时长 8.0s
A 正常   停在：end_turn（正常答完）     第  4 轮  累计   4040 token    0.0s
B 死磕   停在：步数上限               第 15 轮  累计  17071 token    0.0s
C 话痨   停在：token 预算           第  7 轮  累计  40019 token    0.0s
D 慢工具  停在：墙钟超时               第  4 轮  累计   2834 token    8.0s

--no-limits：
B 死磕   停在：步数上限               第 60 轮  累计 179911 token
C 话痨   停在：步数上限               第 60 轮  累计 3081000 token
```

话痨停在 40019 而不是 30000：闸在调模型之前查，第 7 轮放行时累计 29136 还没到。

## 练习

先猜结果，再运行。

1. 调模型前估这一轮的输入（`len(str(messages)) // 3`），会超就不发；“话痨”还会超出预算吗？
2. 把“慢工具”改成一次卡 30 秒，墙钟闸多久生效？给工具包一层 20 秒超时（`concurrent.futures` 的 `result(timeout=20)`）再看。
3. `Outcome` 里加上已经拿到的工具结果，撞闸时打印给值班的人看。
