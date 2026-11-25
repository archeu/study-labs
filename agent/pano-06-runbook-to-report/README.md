# 全景：一份运维手册从上传到被引用

对应页面：[全景：一份运维手册从上传到被引用](https://study.ainiya.de/agent/pano-06-runbook-to-report.html)

值班助手 v3 的知识库，最小实现。`kb.py` 里每个函数对应全景页的一站，站 06 后面的知识点页都从这里取代码。

## 怎么跑

```bash
# 1. 三个模型服务（llama.cpp b12618，从源码编译 llama-server）
llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091 -c 8192 -b 4096 -ub 4096 -np 2
llama-server -m bge-reranker-v2-m3-Q8_0.gguf --reranking --port 8092 -c 8192 -b 4096 -ub 4096 -np 2
llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja --port 8093 -c 12288 -np 1
# 模型：Qwen/Qwen3-Embedding-0.6B-GGUF、gpustack/bge-reranker-v2-m3-GGUF、unsloth/Qwen3-1.7B-GGUF

# 2. PostgreSQL + pgvector
docker run -d --name s06-pg -e POSTGRES_PASSWORD=kb -e POSTGRES_DB=kb -p 127.0.0.1:55433:5432 pgvector/pgvector:pg17

# 3. 生成假资料，跑全景场景
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py           # 生成 data/：手册 v3.1、v3.2 的 PDF，两张故障单，三篇笔记
uv run python -W ignore pano-06-runbook-to-report/main.py manual     # 人写的 4 条检索语句
uv run python -W ignore pano-06-runbook-to-report/main.py naive      # 模型只看任务，自己列语句
uv run python -W ignore pano-06-runbook-to-report/main.py kb-aware   # 告诉模型库里有什么，再列语句
```

地址都能用环境变量改：`EMBED_URL`、`RERANK_URL`、`GEN_URL`、`PG_DSN`。RapidOCR 第一次运行时会把自带的 ONNX 模型放进 `.venv`。

- `corpus.py`：假资料。手册 12 页，每页有页眉页脚，第 2 页是目录，JVM 参数表跨两页，第 12 页（附录 C）渲染成带倾斜和噪点的图片当扫描件；v3.1 和 v3.2 在 §2 的阈值、§4.3 的措辞上不同。
- `kb.py`：sync（哈希比对、旧版下线）→ parse（PyMuPDF 版面块和表格、RapidOCR、python-docx）→ clean / chunk（去页眉页脚和目录、还原连字，按标题切，带元数据）→ embed → store（pgvector + HNSW）→ hybrid（jieba + 手写 BM25、pgvector、RRF）→ rerank → generate → check_citations。
- `main.py`：全景场景。先入库“昨天的”资料，再上传 v3.2 和新故障单，然后写 INC-20261109-02 复盘报告的“根因与处置”一节；结果写到 `runs/pano-run-<计划>.json`。
- `runs/`：2026-11-25 三次真实运行的完整记录。

## 预期输出

2026-11-25，2 核 ARM、11 GB，Python 3.13，PostgreSQL 17.11 + pgvector 0.8.7：

```
09:30 上传：手册 v3.2、11-09 的故障单（站 1～5）
  order-svc-runbook-v3.2.pdf       v3.2   96 块 →  17 片
  INC-20261109-02.docx             v1      8 块 →   4 片
  解析 5.9s（含 OCR）· 切块 0.00s · 向量化 19.0s · 入库 0.07s；库里各状态的片数：{'current': 31, 'retired': 16}
...
站 8 混合检索合计 1.7s · 站 9 重排合计 47.7s · 送进生成的资料 5 段
站 10 生成 22.8s（提示 646 token，输出 110 token）
站 11 引用回查：4 句，3 句带引用，0 句的关键词在被引资料里不到一半
```

三种计划的差别（都是真实结果，`runs/` 里有全文）：

| 计划 | 模型列的语句 | 送进生成的资料 | 报告 |
|---|---|---|---|
| manual | 人写，用手册里的词 | 故障单根因、手册 §4.3、附录 C（OCR）、§4.2、Caffeine 笔记 | 根因和违反的规定写对；止血那句没标出处；“为什么没回滚”标了 §4.2，理由却是反的 |
| naive | 4 条全是“故障 INC-20261109-02 的……” | 只有故障单 2 段 | 写“违反了运维手册……的规定[2]”，[2] 是故障单的基本信息表 |
| kb-aware | 这次是“根因是什么？”这种空话（另一次同样的提示仍带故障编号：温度 0 也不保证一样） | 0 段 | 照样写了三句，标着 [3][4][5]，这三个编号不存在 |

## 练习

先猜结果，再运行。

1. 在 `main.py` 里加一道闸：重排后一段资料都没留下时不调生成模型，改为把“资料不够”交回第 6 站。用 kb-aware 计划验证。
2. 把 `kb.parse_pdf` 里扫描页走 OCR 的那两行注释掉，跑 manual 计划，看“关闭本地缓存的降级开关”这条语句的候选变成什么。
3. 在 `kb.store` 里去掉把旧版本标成 retired 的两行，问“order-04 接口 P99 告警阈值是多少”，看重排后的前 3 段。
4. 给 `check_citations` 加上 `bad_ids` 的报警，让 kb-aware 那次的 [3][4][5] 被查出来。
