# Embedding：文本变向量，意思近的靠得近

对应页面：[Embedding：文本变向量，意思近的靠得近](https://study.ainiya.de/agent/llm-embedding.html)

8 条历史工单、3 个换了说法的值班提问：按字重合 vs 按向量余弦各取前 3；再测否定句和查询指令前缀两个坑。只用标准库。

## 准备

在 [llm-next-token](../llm-next-token/README.md) 编好的 llama.cpp 基础上，下载 Qwen3-Embedding-0.6B（Q8_0，约 640 MB），以向量模式起服务：

```bash
curl -LO https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf
build/bin/llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --port 8092 --embeddings --pooling last -c 2048 -np 1 -t 2 --no-webui
```

## 怎么跑

```bash
cd agent
uv run python llm-embedding/main.py search   # 两种排序各取前 3
uv run python llm-embedding/main.py traps    # 否定句、指令前缀、存储估算
uv run python llm-embedding/main.py dump     # 相似度矩阵 JSON（页面实验台用）
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618，Qwen3-Embedding-0.6B Q8_0，Python 3.13：

```
提问：订单服务 20:15 变慢，疑似 GC 导致
  按字重合 前 3：0.143 堆内存打满触发 OOM… / 0.115 第三方支付网关返回 502 / 0.114 order-04 Full GC…
  按向量余弦 前 3：0.690 老年代回收太频繁… / 0.668 order-04 Full GC… / 0.648 慢 SQL 扫全表…
提问：数据库连不上了，下单一直转圈 → 余弦第一：0.679 MySQL 连接池耗尽，下单请求排队
提问：用户付完钱订单还显示没付款 → 余弦第一：0.676 支付回调超时，订单状态卡在待支付

“是 GC 导致的” vs “不是 GC 导致的”   余弦 0.972
“是 GC 导致的” vs “是数据库导致的”   余弦 0.876
查询前缀：不加 / 模型说明的英文模板 三题第一名都对；自写中文指令有两题第一名变成“慢 SQL”
```

## 练习

1. 加一条“排查后确认不是 GC 导致的接口变慢”，GC 提问把它排第几？
2. 加两条只有订单号不同的工单，用订单号去搜；再用 `in` 精确匹配对照。
3. 加一条英文工单“Frequent old-gen GC causing slow checkout API”，中文 GC 提问能找到它吗？
