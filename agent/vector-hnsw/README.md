# 向量检索和 HNSW 索引

对应页面：[向量检索和 HNSW 索引](https://study.ainiya.de/agent/vector-hnsw.html)

## 怎么跑

```bash
# PostgreSQL + pgvector（建 HNSW 要多给 maintenance_work_mem）
docker run -d --name s06-pg -e POSTGRES_PASSWORD=kb -e POSTGRES_DB=kb -p 127.0.0.1:55433:5432 --shm-size=1g \
  pgvector/pgvector:pg17 -c maintenance_work_mem=1GB -c max_parallel_maintenance_workers=1 -c shared_buffers=512MB
cd agent && uv sync
uv run python -W ignore vector-hnsw/main.py            # 本机十来分钟，写 hnsw.json
uv run python -W ignore vector-hnsw/main.py --filter   # 只重跑带过滤的那组（约 2 分钟）
python3 vector-hnsw/toy_hnsw.py                         # 手推：18 个点、3 层的一次查询
```

- `main.py`：5 万条合成的 1024 维向量（1000 个话题中心加噪声，归一化），100 个问题。暴力扫描；HNSW m=16 / m=8，ef_search 10～320；IVFFlat lists=224，probes 1～40；`WHERE team = …`（2% / 10% / 30%）在规划器自选和强制走索引时、iterative_scan 关和开时的返回条数与召回。
- `toy_hnsw.py`：页面 ⑤ 的小图和搜索轨迹，只用标准库；`--json` 输出页面用的数据。
- 合成数据比真实 Embedding 散（问题和第 1 近的余弦平均 0.38），召回率只对这类数据成立。

## 预期输出

2026-11-25，2 核 ARM，PostgreSQL 17.11 + pgvector 0.8.7：

```
装入 50000 条 1024 维：28.0s，表 267.9 MB；numpy 暴力算一个问题 9.09 ms
暴力扫描（顺序扫全表）：p50 227.75 ms，p95 237.13 ms，召回 1.0
HNSW m=16 ef_search= 10：召回@10 0.837  p50 1.62 ms
HNSW m=16 ef_search= 40：召回@10 0.990  p50 2.2 ms
HNSW m=16 ef_search=160：召回@10 1.000  p50 6.17 ms
  建索引 83.7s，索引 390.6 MB
HNSW m= 8 ef_search= 40：召回@10 0.890  p50 1.74 ms        建索引 45.5s
IVFFlat lists=224 probes=10：召回@10 0.922  p50 5.21 ms    建索引 11.4s
过滤  2% 规划器自选 iterative_scan=off  平均返回 10.0 条，召回 1.0，p50 11.34 ms ｜ Sort / Seq Scan on items
过滤  2% 强制走索引 iterative_scan=off  平均返回  0.9 条（最少 0），召回 0.092
过滤 10% 规划器自选 iterative_scan=off  平均返回  4.0 条（最少 0），召回 0.401 ｜ Index Scan using items_hnsw
过滤 10% 规划器自选 iterative_scan=relaxed_order  平均返回 10.0 条，召回 0.677，p50 5.18 ms
```

注意：psycopg 3 会把同一条 SQL 执行 5 次后自动改成服务端预编译、缓存计划，之后 `SET enable_seqscan = off` 不起作用；所以计时查询都带 `prepare=False`。pgjdbc 的 `prepareThreshold` 默认也是 5。

## 练习

先猜结果，再运行。

1. `toy_hnsw.py` 里 `EF = 1`，换几个 `QUERY`，找一个停在局部最近的例子。
2. 所有点的最高层改成 0、入口放在 A，数第 0 层展开了几个点。
3. 过滤实验里把 `hnsw.ef_search` 调到 1000（不开 iterative_scan），和 `relaxed_order` 比。
4. 按“每条约 5.4 KB 表 + 7.8 KB 索引”估算 300 万片要多少内存；换 `halfvec` 能省多少。
