# 记忆怎么取：按相关性、时间、重要性召回

对应页面：[记忆怎么取：按相关性、时间、重要性召回](https://study.ainiya.de/agent/memory-retrieval.html)

## 怎么跑

```bash
# llama.cpp b12618，模型 Qwen/Qwen3-Embedding-0.6B-GGUF 的 Q8_0
llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
cd agent && uv sync
EMBED_URL=http://127.0.0.1:8091/v1/embeddings uv run python memory-retrieval/main.py
```

- `episodes.py`：交易组 25 条情景记忆 + 风控组 6 条（假数据），每条带日期和 1～10 的重要性。
- `main.py`：先按 team 过滤，再比较五种打分（只看相关 / 新近 / 重要、三项归一后相加、不归一相加）；相似度另存为 `scores.json`（页面实验台用的就是它）。只用标准库。

需要的第三方包用 `uv add <包>` 加到 `agent/pyproject.toml`。

## 预期输出

2026-11-24，2 核 ARM，Python 3.13，Qwen3-Embedding-0.6B Q8_0：

```
全部 31 条，team=trade 过滤后 25 条
只看相关         前 3：['E15', 'E6', 'E10']   （E1 排第 12）
只看新近         前 3：['E1', 'E7', 'E8']
只看重要         前 3：['E6', 'E16', 'E1']
三项归一后相加      前 3：['E15', 'E1', 'E6']
三项相加但不归一     前 3：['E1', 'E3', 'E23']
相似度范围 0.417～0.625；新近分范围（半衰期 30 天）0.015～0.977
```

## 练习

先猜结果，再运行。

1. 写 `parse_time(question, today)`，把“昨天”“上周”换算成日期范围加进过滤，再看只看相关时 E1 排第几。
2. 把问题换成“inventory-svc 磁盘满了怎么办”，看前 3 有几条不相干；加一个原始相似度下限。
3. 去掉 team 过滤跑一次，让“返回的 team 都等于当前团队”的断言报错。
