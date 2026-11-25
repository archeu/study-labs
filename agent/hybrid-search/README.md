# 混合检索：关键词 BM25 加向量，再重排

对应页面：[混合检索：关键词 BM25 加向量，再重排](https://study.ainiya.de/agent/hybrid-search.html)

## 怎么跑

要 8091（Embedding）和 8092（重排）两个 llama-server，命令见 [pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md)。BM25、RRF、重排的本体在 `pano-06-runbook-to-report/kb.py`。

```bash
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py
uv run python -W ignore hybrid-search/main.py      # 本机约 7 分钟（重排 30 × 20 对），写 hybrid.json
```

- `main.py`：31 片、站 06 评测集 30 题，6 种办法：只走 BM25（jieba + 手写公式）、BM25 不按片长打折（b=0）、只走向量、RRF（k=60，两路各取前 20）、min-max 归一化后加权（α=0.5）、RRF 前 20 + bge-reranker-v2-m3 重排。另打印 BM25 逐词拆解和 jieba 对标识符的切法。
- `hybrid.json`：每道题两路的完整排名、正确片、各办法的名次；页面实验台用它现算加权 RRF。

## 预期输出

2026-11-25，2 核 ARM，Qwen3-Embedding-0.6B Q8_0 + bge-reranker-v2-m3 Q8_0（llama.cpp b12618）：

```
bm25      @1 0.70  @3 0.90  @5 0.90  MRR 0.802
bm25_b0   @1 0.67  @3 0.87  @5 0.90  MRR 0.775
vector    @1 0.77  @3 0.90  @5 0.97  MRR 0.836
rrf       @1 0.73  @3 0.90  @5 0.97  MRR 0.830
weighted  @1 0.83  @3 0.97  @5 0.97  MRR 0.893
rerank    @1 0.90  @3 0.97  @5 0.97  MRR 0.931
每个问题平均耗时： {'bm25_ms': 0.45, 'vector_ms': 254.9, 'rerank_ms': 12295}
按题型，前 1 片命中：table BM25 0.75 / 向量 0.25；para BM25 0.69 / 向量 1.0；ocr 1.0 / 0.67；ticket 0 / 0
jieba：'order.cache.local.enabled' → ['order', 'cache', 'local', 'enabled']
```

30 题里一道题是 3.3 个百分点，RRF 和向量之间只差一道。

## 练习

先猜结果，再运行。

1. `kb.tokenize()` 里去掉“怎么、什么、哪个、哪里、能不能、的”，看 Q29、Q03 的 BM25 排名。
2. `jieba.add_word()` 加上三个开关名和故障编号，看开关那题的 BM25 分差。
3. 两路第 1 名相同就不重排，数能省几次、命中率掉不掉。
4. 打印重排最高分小于 0 的题，看是不是前 20 里本来就没有对的片。
