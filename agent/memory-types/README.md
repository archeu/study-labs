# 记忆分几种：短期和长期，情景、语义、程序性

对应页面：[记忆分几种：短期和长期，情景、语义、程序性](https://study.ainiya.de/agent/memory-types.html)

## 怎么跑

```bash
# llama.cpp b12618 的 llama-server，模型 Qwen/Qwen3-Embedding-0.6B-GGUF 的 Q8_0
llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
cd agent && uv sync
EMBED_URL=http://127.0.0.1:8091/v1/embeddings uv run python memory-types/main.py
```

- `memories.py`：交易组 12 条记忆（5 情景、3 程序性、4 语义），每条带 kind、team、key、date。
- `main.py`：一锅端（只按相似度取前 3）和按类型取（程序性按范围、语义按 key、情景按相似度 × 新近）对比。只用标准库调 embedding。

需要的第三方包用 `uv add <包>` 加到 `agent/pyproject.toml`。

## 预期输出

2026-11-24，2 核 ARM，Python 3.13，Qwen3-Embedding-0.6B Q8_0：

```
一锅端前 3：S3 0.576、P3 0.562、P1 0.557；E1（昨天那次）0.538 排第 4，S1（堆 4 GB）排第 8
按类型：程序性 P1 P3；语义 S1 S3；情景 E1 0.536、E3 0.51
```

## 练习

先猜结果，再运行。

1. 换问法（“现在能不能做 heap dump？”“order-svc 堆多大？”）各跑一次，先猜一锅端前 3。
2. 加一条 S5 “堆 8 GB”，给语义记忆写 upsert：同一个 key 只留最新。
3. 让站 04 `oncall.py` 的 `heap_dump` 在 19:00–22:00 直接返回 is_error，再想 P1 还要不要留。
