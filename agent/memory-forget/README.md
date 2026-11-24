# 记忆怎么忘：衰减、过期，按用户隔离和隐私

对应页面：[记忆怎么忘：衰减、过期，按用户隔离和隐私](https://study.ainiya.de/agent/memory-forget.html)

## 怎么跑

不需要模型，只用 Python 自带的 sqlite3（内存库）：

```bash
cd agent && uv sync
uv run python memory-forget/main.py
```

- `forget.py`：90 天模拟数据（固定种子）、每晚的遗忘任务（过期删除、衰减归档、宽规则脱敏）、带 / 不带团队条件的召回、结果统计。
- `main.py`：四套策略对比和隔离对比；另把 3×2×3 种组合写进 `grid.json`（页面实验台查的就是它）。

需要的第三方包用 `uv add <包>` 加到 `agent/pyproject.toml`。

## 预期输出

2026-11-24，Python 3.13：

```
90 天共写入 332 条（交易组 298 条，其中 40 条日志片段带手机号）
策略                 活跃   归档  P0在   规矩在    过期临时   手机号残留    低价值旧病历
什么都不忘             298    0    是     是      26      40        44
一刀切：30 天全删         91    0    否     是       0       3         0
只做写入时脱敏 + TTL     272    0    是     是       0      18        44
本页做法              259   13    是     是       0       0        31
召回时漏了 team 条件：交易组的人能看到 34 条别的团队的记忆；带上条件：0 条
```

## 练习

先猜结果，再运行。

1. 给 `recall` 加 `expires IS NULL OR expires > now`，模拟某晚任务没跑，看过期临时会不会被取到。
2. 写 `erase(db, phone)`：活跃表和冷表都删，返回条数，写一条不含原文的审计记录。
3. 用 Docker 起 PostgreSQL 17，给 memories 开行级安全（记得 FORCE），确认不带条件也看不到别的团队。
