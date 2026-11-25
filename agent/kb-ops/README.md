# 知识库运维：增量同步、权限、版本

对应页面：[知识库运维：增量同步、权限、版本](https://study.ainiya.de/agent/kb-ops.html)

## 怎么跑

要 8091（Embedding）和 pgvector 容器，见 [pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md)。

```bash
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py
uv run python -W ignore kb-ops/main.py      # 本机约 4 分钟，写 ops.json；库目录在 kb-ops/data/（不进仓库）
```

- `kb_ops.py`：`sync()` 三层身份（文件哈希 → 名字 + 版本号 → 片哈希）、复用向量、按版本号定现行版、文件消失就软删、目录即团队；`search()` 只查 current 和有权限的团队。
- `main.py`：一天 8 次变更（首次入库、上传新版、无变更、原地修订、加新手册、挪目录改权限、旧版改错字、删一篇），每步同步一次并查一个问题；最后从零全量重建对照。

## 预期输出

2026-11-25，2 核 ARM，PostgreSQL 17.11 + pgvector 0.8.7，Qwen3-Embedding-0.6B Q8_0：

```
首次入库        算 26 片                          27.8 s
09:30 上传新版  算 21 片，v3.1 的 16 片 → retired   25.1 s
再跑一次        全部跳过                           0.0 s
改一处          算 1 片，保留 16，删 1              7.0 s（主要是重新解析和 OCR）
加一份新手册    算 3 片                            1.9 s，HNSW 不重建，供应链组马上查得到
改权限          supply → public，只改权限，0 片     0.0 s
旧版改错字      v3.1 算 0 片，v3.2 仍是现行          5.6 s
删一篇          1 片 → deleted                     0.0 s
对照：全量重建  算 49 片                           53.1 s
```

## 练习

先猜结果，再运行。

1. 按渲染图的哈希缓存 OCR，看“改一处”“旧版改错字”快了多少。
2. 问题里的服务名不在任何一片里就拒答，用交易组在“改权限”之前那一步验证。
3. 把 `EMBED_MODEL` 改个名字再同步，库里出现两种 embed_model 就报警。
4. 给 `search()` 加 version 参数，查 v3.1 的 P99 阈值。
