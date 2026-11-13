# 工单库要加“找相似故障”：MySQL 还是 PostgreSQL，pgvector 凭什么

对应页面：[工单库要加“找相似故障”：MySQL 还是 PostgreSQL，pgvector 凭什么](https://archeu.github.io/study-notes/database/mysql-vs-pg-pgvector.html)

值班助手的故障单库：业务字段和向量放在同一个 PostgreSQL 里（pgvector）。向量优先调本机 ollama 的 `bge-m3`（1024 维），连不上就退回“字二元组哈希”向量（只能比字面，⑤ 的排序会和下面不同）。只依赖 pgJDBC，向量按文本 `'[0.1,0.2,…]'` 传，SQL 里 `?::vector`。

- `VecLab setup`：装扩展、建 `tickets`，16 张故障单连同向量在一个事务里写入
- `VecLab see`：⑤ 一条 SQL 里同时按服务、时间过滤和按向量排序；新单子写入后回滚 / 提交，搜得到搜不到
- `VecLab filter`：10 万行 128 维（200 个团 + 噪声）+ HNSW：带 WHERE 时结果少于 LIMIT；`hnsw.iterative_scan`；加 B-tree 后计划不变；改写成 MATERIALIZED CTE 走“先过滤再精确”

## 怎么跑

需要 Docker（会拉 `pgvector/pgvector:pg18`）；可选 ollama + `ollama pull bge-m3`（不在 127.0.0.1:11434 时加 `-Dollama=http://host:port`）。按 JDK 8 写法（`--release 8`），用 JDK 17 跑过。整套约 1 分钟（建 HNSW 约 14 s）。

```bash
cd java/database/mysql-vs-pg-pgvector
DOCKER="sudo docker" ./run.sh         # 起容器 → setup → see → filter
DOCKER="sudo docker" ./run.sh again   # 容器留着，重跑
DOCKER="sudo docker" ./run.sh clean   # 删容器
```

容器带了 `--shm-size=1g`：并行建 HNSW 时图放在共享内存里，Docker 默认的 64 MB `/dev/shm` 会报 `could not resize shared memory segment … No space left on device`（第一次跑就踩到了）。

## 预期输出

本机 2 核 ARM，JDK 17.0.21，pgJDBC 42.7.14，Docker 里 PostgreSQL 18.7 + pgvector 0.8.7（`shared_buffers=512MB`），ollama bge-m3：

```
[setup] PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2) | pgvector 0.8.7
[setup] 向量化 16 张单子：ollama bge-m3，1024 维，6.0 s
[setup] tickets 16 行，整表 152 kB；一个 vector(1024) 占 4100 字节（4 字节 × 维数 + 8 字节头）
[see] 新告警：“付款的时候数据库连接不够用，请求一直在等”（bge-m3）
[see] 只按向量排，不过滤：
      #1  payment  付款接口大量超时               距离 0.240
      #3  payment  付款后订单仍显示待付款            距离 0.265
      #5  order    下单接口卡住，大量线程 WAITING    距离 0.294
      #4  payment  付款服务升级后连不上数据库          距离 0.315
      #14 payment  退款接口偶发超时               距离 0.327
[see] 同一条 SQL 再加业务条件：service='payment' 且 90 天内
      #1  payment  P1 付款接口大量超时               距离 0.240
      #3  payment  P1 付款后订单仍显示待付款            距离 0.265
      #14 payment  P2 退款接口偶发超时               距离 0.327
[see] 写入 #17 后回滚：
      #1  payment  P1 付款接口大量超时               距离 0.240
      #3  payment  P1 付款后订单仍显示待付款            距离 0.265
      #14 payment  P2 退款接口偶发超时               距离 0.327
[see] 再写一次 #18 并提交：
      #18 payment  P1 付款高峰期数据库连接池耗尽          距离 0.227
      #1  payment  P1 付款接口大量超时               距离 0.240
      #3  payment  P1 付款后订单仍显示待付款            距离 0.265
[filter] items 100000 行 × 128 维，灌数 2.6 s，表 56 MB
[filter] 建 HNSW（m=16, ef_construction=64）13.9 s，索引 79 MB
[filter] 不过滤                精确扫描  46.2 ms | HNSW 平均返回 10.0 行（最少 10），召回  89%，  0.8 ms | 加 iterative_scan 返回 10.0 行，召回  89%，  1.1 ms
[filter] kind = 3（约 10%）    精确扫描  17.1 ms | HNSW 平均返回  4.3 行（最少 1），召回  41%，  0.7 ms | 加 iterative_scan 返回 10.0 行，召回  91%，  1.1 ms
[filter] tenant = 7（约 1%）   精确扫描  13.4 ms | HNSW 平均返回  0.4 行（最少 0），召回   3%，  0.9 ms | 加 iterative_scan 返回 10.0 行，召回  81%，  4.0 ms
[filter] tenant = 7 的执行计划（只有 HNSW）：Limit → Index Scan using items_hnsw on items → Filter: (tenant = 7)
[filter] 加 B-tree(tenant) 之后：            Limit → Index Scan using items_hnsw on items → Filter: (tenant = 7)
[filter] 加 B-tree 后 tenant = 7：平均返回 0.4 行，召回 3%，0.8 ms（计划没变）
[filter] 改写成 MATERIALIZED CTE：Limit → CTE t → Bitmap Heap Scan on items → Recheck Cond: (tenant = 7) → Bitmap Index Scan on items_tenant → Index Cond: (tenant = 7) → Sort → CTE Scan on t
[filter] 先过滤再精确排序：平均返回 10.0 行，召回 100%，1.5 ms
```

召回随查询向量有几个百分点的浮动（两次运行 1% 那一行 iterative scan 是 77% 和 81%）。

## 练习

先猜结果，再运行。

1. 给 `tickets` 建 `hnsw (embedding vector_cosine_ops)`，`SET enable_seqscan = off` 后看 `EXPLAIN`；再把 `<=>` 换成 `<->`，计划怎么变？
2. 在 `filter` 里加一种走法：部分索引 `CREATE INDEX … USING hnsw (emb vector_cosine_ops) WHERE tenant = 7`，和 CTE 方案比返回行数、召回、耗时。
3. 把 `embedding` 改成 `halfvec(1024)`，比较 TOAST 表大小，⑤ 的排序变不变。
