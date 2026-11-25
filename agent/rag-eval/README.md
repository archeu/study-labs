# 评测：检索命中率和答案忠实度

对应页面：[评测：检索命中率和答案忠实度](https://study.ainiya.de/agent/rag-eval.html)

## 怎么跑

要 8091、8092、8093 三个 llama-server（[pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md)），资料用 `corpus.py` 生成过。

```bash
cd agent && uv sync
uv run python -W ignore rag-eval/main.py      # 33 题，本机约 25 分钟，写 answers.json
# 人工核对：对照每题拿到的 3 片资料，填 labels.json（faithful / correct / cite_ok / note）
uv run python rag-eval/score.py               # 写 eval.json
```

- `main.py`：评测集 30 题 + 3 道库外题（`pano-06-runbook-to-report/questions.py`）。检索 = BM25 + 向量 + RRF 前 20 + 重排，前 3 片交给 Qwen3-1.7B；判官 1 是关键词覆盖（`kb.check_citations`），判官 2 是同一个 Qwen3-1.7B 输出 JSON。
- `labels.json`：33 条人工核对，标注时只看每题实际拿到的 3 片资料；“资料里没有”的误拒记为忠实但答错。
- `score.py`：检索指标、忠实度、正确率、错因交叉表，以及两个判官在不同阈值下和人工比的召回、精确。

## 预期输出

2026-11-25，2 核 ARM，llama.cpp b12618：

```
检索： {'hit@1': 0.9, 'hit@3': 0.967, 'hit@5': 0.967, 'mrr': 0.931}
生成： 忠实 29 / 33（0.879，95% 区间 0.77～0.99）；答对 20、部分对 3、答错 7；库外题说没有 2 / 3
错在哪一段： {'检索到了': {'yes': 20, 'no': 6, 'partial': 3}, '没检索到': {'no': 1}}
小模型判官： {'tp': 0, 'fp': 0, 'fn': 4, 'tn': 29, 'recall': 0.0}
关键词判官 阈值 0.5： {'tp': 1, 'fp': 6, 'fn': 3, 'tn': 23}
关键词判官 阈值 0.8： {'tp': 4, 'fp': 11, 'fn': 0, 'tn': 18}
每题平均耗时（秒）： {'t_retrieve': 12.5, 't_gen': 12.5, 't_judge': 15.0}
```

## 练习

先猜结果，再运行。

1. `RULE` 里允许简单换算（晚上八点 = 20:00），只重跑 Q05、Q06、Q27，再看 N01、N03 会不会开始编。
2. 问题里的服务名在资料里一次都没出现就直接答“资料里没有”，重跑 N01～N03。
3. 判官改成先为每句抄出原文依据、再判断，看能不能抓住 Q11、Q18。
4. 同样设置再跑一遍，逐题比较两份 answers.json。
