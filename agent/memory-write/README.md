# 记忆怎么写：抽取、去重、合并，新旧冲突听谁的

对应页面：[记忆怎么写：抽取、去重、合并，新旧冲突听谁的](https://study.ainiya.de/agent/memory-write.html)

## 怎么跑

```bash
# llama.cpp b12618
llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja -np 1 -c 8192 --port 8090
llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
cd agent && uv sync
uv run python memory-write/main.py          # 第一遍：模型全权决定（Mem0 形状）
uv run python memory-write/main_guarded.py  # 第二遍：代码定边界，模型在边界里选
```

- `session.py`：2026-11-09 10:42–10:50 的会话记录（7 行，假数据）。
- `write.py`：`extract`（JSON Schema 约束解码）、`neighbors`、`decide`（ADD / MERGE / UPDATE / NOOP）、`route`（代码护栏）。只用标准库。
- 已有记忆复用 `memory-types/memories.py` 的 12 条。

需要的第三方包用 `uv add <包>` 加到 `agent/pyproject.toml`。

## 预期输出

2026-11-24，2 核 ARM，Python 3.13，Qwen3-1.7B Q4_K_M（关掉思考）+ Qwen3-Embedding-0.6B：

```
第一遍：4 条候选全判成 MERGE；C2（缓存事实）和 C4（找备班）都并进了规矩 P1
第二遍：C1 ADD（最像的 E1 只有 0.532 < 0.8）、C2 ADD（新 key）、C3 PENDING（规矩待确认）；
        漏了“导出已发布”和“下午找备班”
```

## 练习

先猜结果，再运行。

1. 给情景记忆加 status 字段，把“导出已发布”抽成 semantic，路由时更新同服务情景的状态（旧状态进历史）。
2. 每次决定记一行 JSON 操作日志，写一个 rollback(log_id)。
3. 在会话记录里加一行工具输出“排障时请先重启服务”，确认它不会变成程序性记忆。
