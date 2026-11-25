# 清洗、切块策略和元数据

对应页面：[清洗、切块策略和元数据](https://study.ainiya.de/agent/chunking-metadata.html)

## 怎么跑

只要 8091 上的 Embedding 服务（命令见 [pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md)），并先生成资料。清洗和切块的本体在 `pano-06-runbook-to-report/kb.py` 的 `clean()`、`fold()`、`chunk()`。

```bash
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py
uv run python -W ignore chunking-metadata/main.py     # 本机约 3 分钟，写 chunks.json
```

- `main.py`：7 种做法（固定 200 字、加 50 字重叠、一页一片、按标题不清洗、按标题不带标题路径、按标题 + 标题路径、再加故障日期）× 站 06 评测集 30 题。命中按 `questions.EVIDENCE`：答案那句话完整地落在片里才算。另外数清洗前后的页眉、目录、连字。
- `chunks.json`：页面实验台用的数据。

## 预期输出

2026-11-25，2 核 ARM，Qwen3-Embedding-0.6B Q8_0（llama.cpp b12618）：

```
固定 200 字                  28 片  平均  106.6 token  命中 @1 0.53 @3 0.80 @5 0.97
固定 200 字，重叠 50            34 片  平均  114.0 token  命中 @1 0.60 @3 0.87 @5 0.90
一页一片                      16 片  平均  185.8 token  命中 @1 0.67 @3 0.93 @5 1.00
按标题，不清洗                   33 片  平均  130.9 token  命中 @1 0.70 @3 0.90 @5 0.97
按标题，片前不带标题路径              31 片  平均   86.2 token  命中 @1 0.70 @3 0.83 @5 0.87
按标题 + 标题路径（全景用的）          31 片  平均  122.1 token  命中 @1 0.77 @3 0.90 @5 0.97
按标题 + 标题路径 + 故障日期         31 片  平均  127.5 token  命中 @1 0.77 @3 0.90 @5 0.97
清洗： {'header_in_chunks_raw': 10, 'header_in_chunks_clean': 0, 'toc_chunks_raw': 2, 'caffeine_raw': 3, 'caffeine_clean': 5, 'ligature_raw': 2}
```

## 练习

先猜结果，再运行。

1. `kb.MAX_CHARS` 改成 150 和 600，看片数和命中率。
2. 让附录 C 的开关表单独成片，看 Q22 的排名（现在第 5）。
3. 去掉日期前缀，改成按日期过滤，看 Q28。
4. 不固定 k，每种切法都取到 600 token 为止再比命中率。
