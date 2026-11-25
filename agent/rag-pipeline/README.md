# RAG 流水线：切块、向量化、检索、重排、生成

对应页面：[RAG 流水线：切块、向量化、检索、重排、生成](https://study.ainiya.de/agent/rag-pipeline.html)

## 怎么跑

先按 [pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md) 起三个 llama-server（8091 Embedding、8092 重排、8093 生成），并生成假资料。这一页不用数据库。

```bash
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py                                   # 生成 data/
uv run python -W ignore rag-pipeline/rag_min.py --closed-book "想临时停用本地缓存，改哪个开关？"
uv run python -W ignore rag-pipeline/rag_min.py "想临时停用本地缓存，改哪个开关？"
uv run python -W ignore rag-pipeline/rag_min.py --rerank "想临时停用本地缓存，改哪个开关？"
uv run python -W ignore rag-pipeline/main.py                                         # 两个实测，写 hitk.json
```

- `rag_min.py`：最小 RAG，一百行。切块借用全景的 `kb.py`；向量放 numpy 矩阵里暴力算余弦；`--rerank` 先取 10 片再重排留 3 片；`--closed-book` 不给资料直接问。
- `main.py`：① 3 个问题的闭卷 / RAG / RAG + 重排对照；② 30 题（`pano-06-runbook-to-report/questions.py`）只走向量检索，正确的片排第几，前 k 片命中率，每片多少 token。
- `hitk.json`：页面实验台用的数据。

## 预期输出

2026-11-25，2 核 ARM，Python 3.13，llama.cpp b12618（Qwen3-Embedding-0.6B Q8_0、bge-reranker-v2-m3 Q8_0、Qwen3-1.7B Q4_K_M）：

```
Q13 发布后多长时间内出问题可以不查原因直接回滚？
  闭卷：一般情况下，发布后24小时内发现问题，可以不查原因直接回滚。
  RAG：发布后 30 分钟内出问题可以不查原因直接回滚 [1]。
Q22 想临时停用本地缓存，改哪个开关？
  闭卷：cache.disable または cache.off
  RAG：资料里没有提到具体改哪个开关……（随后复读到 200 token）
  先取 10 再重排留 3：附录C 1.196、时间线 -1.888、4.3 -2.953
  RAG+重排：…order.cache.local.enabled…

前 k 片命中率：k=1:0.77 k=2:0.80 k=3:0.90 k=4:0.93 k=5:0.97 k=6:0.97 k=7:0.97 k=8:1.00
  table    4 题，排名 [2, 3, 1, 4]
  ocr      3 题，排名 [5, 1, 1]
  ticket   3 题，排名 [3, 3, 8]
每片平均 86.2 token（最长 211）；本机读提示 38.4 token/s，生成 10.0 token/s
```

小模型温度 0 也不保证每次一样：重排那次的出处编号，一次写 [1]、一次写 [2]。

## 练习

先猜结果，再运行。

1. `--rerank` 时重排最高分小于 0 就不调生成模型，直接返回“知识库里没找到”；问“pay-svc 的 P99 告警阈值是多少？”。
2. 去掉 `retrieve()` 里的 `Instruct: …`，跑 `main.py`，看 k=1 的命中率（本机是 0.77 → 0.70）。
3. 在 `kb.Chunk.embed_text()` 里给故障单的片带上“2026 年 11 月 8 日”这样的日期，看 Q27～Q29 的排名。
4. `max_tokens` 改成 80，再传 `repeat_penalty: 1.1`，看不带重排时还复读不复读。
