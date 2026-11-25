# RAG 优化：查询改写、元数据过滤、父子块

对应页面：[RAG 优化：查询改写、元数据过滤、父子块](https://study.ainiya.de/agent/rag-optimize.html)

## 怎么跑

要 8091（Embedding）；`rewrite` 还要 8093（生成），见 [pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md)。

```bash
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py
uv run python -W ignore rag-optimize/main.py filter parent rewrite fuse   # 本机约 15 分钟，写 opt.json
```

- `filter`：把手册 v3.1 的 16 片放回库里（不下线），看前 3 片里混进旧版的题有几道、只查 current 后命中率；问题里的“11 月 X 日”用正则抽出来，只留那天开始的故障单。
- `parent`：每节按句子（表格按行）拆成子，子带标题路径算向量；检索子、按最好名次排父。
- `rewrite`：Qwen3-1.7B 把问题改写成书面说法；HyDE 先写一段假答案，拿它算向量。`opt.json` 里有每题的改写原文。
- `fuse`：原问题、改写、假答案各检索一次，RRF 合并（不再调模型）。

## 预期输出

2026-11-25，2 核 ARM，30 题：

```
v3.1 没下线（47 片）：26 / 30 题的前 3 片里有旧版；不过滤 @3 0.867 → 只查 current @3 0.933
日期过滤：Q27 第 3 → 第 2，Q28 第 4 → 第 2
父子块：31 个父（平均 86.2 token），121 个子（平均 21.6 token）
  按整节检索 @1 0.767 @3 0.9 → 按句检索再换成整节 @1 0.833 @3 0.933
改写：只走向量 @1 0.767 → 0.6；混合检索 0.733 → 0.667；HyDE（混合）0.8
多路查询：原问 + 改写 @1 0.7 @3 0.967；原问 + HyDE 0.8 / 0.9
```

1.7B 模型的改写会编细节（“P99 超过 99.9%”“接口编号 12345”、繁体字），HyDE 的假答案事实全错（把止血写成血小板），但用词像手册。

## 练习

先猜结果，再运行。

1. `REWRITE` 里加“不要补充原问题里没有的数字、名字和编号”，重跑 `rewrite fuse`。
2. 日期过滤后一片不剩就退回不过滤，用“11 月 10 日那次故障是什么原因”试。
3. 子改成“这一句 + 前一句”，看 Q27、Q29。
4. 把日期过滤和父子块接进 `kb.hybrid()`，用 `rag-eval/main.py` 重跑 33 题。
