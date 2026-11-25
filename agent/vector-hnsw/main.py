"""向量检索和 HNSW 的本机实测：PostgreSQL 17 + pgvector，5 万条 1024 维向量。

向量是合成的：1000 个“话题中心”各带 50 条附近的点，再归一化。真实 Embedding 也是一团一团的，
但分布不完全一样，所以这里的召回率是“这类数据上”的数，换成真实数据要重测。
1. 暴力扫描（不用索引）多慢
2. HNSW：建索引用时、索引大小；ef_search 从 10 到 320，召回率和延迟
3. IVFFlat：lists=224，probes 从 1 到 40，对照
4. 带 WHERE 过滤（2% / 10% / 30% 的行满足）：规划器选不选 HNSW，结果够不够 10 条，iterative_scan 管不管用
需要 pgvector 容器（见 pano-06-runbook-to-report/README.md，本实验建议加 -c maintenance_work_mem=1GB）。
uv run python -W ignore vector-hnsw/main.py            # 本机十来分钟，写 hnsw.json
uv run python -W ignore vector-hnsw/main.py --filter   # 只重跑带过滤的那组（约 2 分钟）
"""
import json
import os
import time
from pathlib import Path

import numpy as np
import psycopg

HERE = Path(__file__).resolve().parent
DSN = os.environ.get("PG_DSN", "postgresql://postgres:kb@127.0.0.1:55433/kb")
N, DIM, CENTERS, NQ, K = 50_000, 1024, 1000, 100, 10
rng = np.random.default_rng(7)


def unit(x):
    return x / np.linalg.norm(x, axis=-1, keepdims=True)


def make():
    c = unit(rng.standard_normal((CENTERS, DIM)).astype(np.float32))
    lab = rng.integers(0, CENTERS, N)
    X = unit(c[lab] + 0.045 * rng.standard_normal((N, DIM)).astype(np.float32))
    ql = rng.integers(0, CENTERS, NQ)
    Q = unit(c[ql] + 0.045 * rng.standard_normal((NQ, DIM)).astype(np.float32))
    return X, Q


def lit(v):
    return "[" + ",".join(f"{x:.5f}" for x in v) + "]"


def timed_queries(cur, Q, sql, extra=()):
    ids, ms = [], []
    for q in Q:
        t = time.perf_counter()
        cur.execute(sql, (lit(q), *extra), prepare=False)   # 不让 psycopg 自动预编译：缓存的计划不认后来 SET 的参数
        ids.append([r[0] for r in cur.fetchall()])
        ms.append((time.perf_counter() - t) * 1000)
    return ids, round(float(np.median(ms)), 2), round(float(np.percentile(ms, 95)), 2)


def recall(got, truth):
    return round(float(np.mean([len(set(g) & set(t)) / len(t) for g, t in zip(got, truth)])), 3)


def team_of(x):
    return "trade" if x < 0.02 else "risk" if x < 0.12 else "ops" if x < 0.42 else "other"


def filter_runs(cur, X, Q):
    """带 WHERE team = … 的近邻查询：规划器选不选 HNSW、结果够不够 10 条、iterative_scan 管不管用。"""
    cur.execute("SELECT id, team FROM items")
    teams = dict(cur.fetchall())
    res = {}
    for team, share in (("trade", "2%"), ("risk", "10%"), ("ops", "30%")):
        ids = np.array(sorted(i for i, t in teams.items() if t == team))
        truth = [list(ids[np.argsort(-(X[ids] @ q))[:K]]) for q in Q]
        sql = f"SELECT id FROM items WHERE team = '{team}' ORDER BY emb <=> %s::vector LIMIT 10"
        for force in (False, True):
            cur.execute("SET enable_seqscan = " + ("off" if force else "on"))
            for mode in ("off", "relaxed_order"):
                cur.execute(f"SET hnsw.iterative_scan = {mode}")
                cur.execute("EXPLAIN " + sql.replace("%s", "'" + lit(Q[0]) + "'"))
                plan = " / ".join(r[0].strip().lstrip("-> ").split("  (")[0] for r in cur.fetchall()
                                  if "Scan" in r[0] or r[0].strip().startswith("->  Sort"))
                got, p50, p95 = timed_queries(cur, Q, sql)
                key = f"{team}|{'force' if force else 'auto'}|{mode}"
                res[key] = {"share": share, "plan": plan, "avg_returned": round(float(np.mean([len(g) for g in got])), 1),
                            "min_returned": int(min(len(g) for g in got)), "recall": recall(got, truth), "p50_ms": p50}
                print(f"过滤 {share:>3s} {'强制走索引' if force else '规划器自选'} iterative_scan={mode:13s} "
                      f"平均返回 {res[key]['avg_returned']:4} 条（最少 {res[key]['min_returned']}），召回 {res[key]['recall']}，"
                      f"p50 {p50} ms ｜ {plan}")
    cur.execute("SET enable_seqscan = on"); cur.execute("SET hnsw.iterative_scan = off")
    return res


def main():
    only_filter = "--filter" in __import__("sys").argv
    out = json.loads((HERE / "hnsw.json").read_text()) if only_filter else {"N": N, "DIM": DIM}
    X, Q = make()
    sims = Q @ X.T
    truth = [list(np.argsort(-s)[:K]) for s in sims]
    out["cos_top1_mean"] = round(float(np.mean(np.sort(sims, axis=1)[:, -1])), 3)
    out["cos_10th_mean"] = round(float(np.mean(np.sort(sims, axis=1)[:, -10])), 3)
    out["cos_median"] = round(float(np.median(sims)), 3)
    t = time.perf_counter(); _ = Q[:1] @ X.T; out["numpy_one_query_ms"] = round((time.perf_counter() - t) * 1000, 2)

    conn = psycopg.connect(DSN, autocommit=True)
    cur = conn.cursor()
    cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
    cur.execute("DROP TABLE IF EXISTS items")
    cur.execute(f"CREATE TABLE items (id int PRIMARY KEY, team text, emb vector({DIM}))")
    t = time.perf_counter()
    u = rng.random(N)                   # 2% trade，10% risk，30% ops，其余 other
    with cur.copy("COPY items (id, team, emb) FROM STDIN") as cp:
        for i, v in enumerate(X):
            cp.write_row((i, team_of(u[i]), lit(v)))
    out["load_s"] = round(time.perf_counter() - t, 1)
    cur.execute("VACUUM ANALYZE items")
    if only_filter:
        cur.execute("CREATE INDEX items_hnsw ON items USING hnsw (emb vector_cosine_ops)")
        out["filter"] = filter_runs(cur, X, Q)
        (HERE / "hnsw.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
        cur.execute("DROP TABLE items")
        return
    cur.execute("SELECT pg_total_relation_size('items')")
    out["table_mb"] = round(cur.fetchone()[0] / 2**20, 1)
    print(f"装入 {N} 条 {DIM} 维：{out['load_s']}s，表 {out['table_mb']} MB；numpy 暴力算一个问题 {out['numpy_one_query_ms']} ms")
    print(f"问题和第 1 近的余弦平均 {out['cos_top1_mean']}，第 10 近 {out['cos_10th_mean']}，和随便一条 {out['cos_median']}")

    knn = "SELECT id FROM items ORDER BY emb <=> %s::vector LIMIT 10"
    cur.execute("SET enable_indexscan = off")
    got, p50, p95 = timed_queries(cur, Q[:30], knn)
    out["exact"] = {"p50_ms": p50, "p95_ms": p95, "recall": recall(got, truth[:30])}
    cur.execute("RESET enable_indexscan")
    print(f"暴力扫描（顺序扫全表）：p50 {p50} ms，p95 {p95} ms，召回 {out['exact']['recall']}")

    for m in (16, 8):
        cur.execute("DROP INDEX IF EXISTS items_hnsw")
        t = time.perf_counter()
        cur.execute(f"CREATE INDEX items_hnsw ON items USING hnsw (emb vector_cosine_ops) WITH (m = {m}, ef_construction = 64)")
        bt = round(time.perf_counter() - t, 1)
        cur.execute("SELECT pg_relation_size('items_hnsw')")
        size = round(cur.fetchone()[0] / 2**20, 1)
        rows = []
        for ef in (10, 20, 40, 80, 160, 320):
            cur.execute(f"SET hnsw.ef_search = {ef}")
            got, p50, p95 = timed_queries(cur, Q, knn)
            rows.append({"ef": ef, "recall": recall(got, truth), "p50_ms": p50, "p95_ms": p95})
            print(f"HNSW m={m:2d} ef_search={ef:3d}：召回@10 {rows[-1]['recall']:.3f}  p50 {p50} ms  p95 {p95} ms")
        out[f"hnsw_m{m}"] = {"build_s": bt, "index_mb": size, "rows": rows}
        print(f"  建索引 {bt}s，索引 {size} MB")
        cur.execute("RESET hnsw.ef_search")
    # IVFFlat 要和 HNSW 分开测：先删 HNSW
    cur.execute("DROP INDEX IF EXISTS items_hnsw")
    t = time.perf_counter()
    cur.execute("CREATE INDEX items_ivf ON items USING ivfflat (emb vector_cosine_ops) WITH (lists = 224)")
    ivf = {"build_s": round(time.perf_counter() - t, 1), "rows": []}
    cur.execute("SELECT pg_relation_size('items_ivf')")
    ivf["index_mb"] = round(cur.fetchone()[0] / 2**20, 1)
    for p in (1, 5, 10, 20, 40):
        cur.execute(f"SET ivfflat.probes = {p}")
        got, p50, p95 = timed_queries(cur, Q, knn)
        ivf["rows"].append({"probes": p, "recall": recall(got, truth), "p50_ms": p50, "p95_ms": p95})
        print(f"IVFFlat lists=224 probes={p:2d}：召回@10 {ivf['rows'][-1]['recall']:.3f}  p50 {p50} ms")
    print(f"  建索引 {ivf['build_s']}s，索引 {ivf['index_mb']} MB")
    out["ivfflat"] = ivf
    cur.execute("DROP INDEX items_ivf")
    cur.execute("CREATE INDEX items_hnsw ON items USING hnsw (emb vector_cosine_ops)")

    out["filter"] = filter_runs(cur, X, Q)
    cur.execute("SHOW hnsw.ef_search"); out["ef_default"] = cur.fetchone()[0]
    cur.execute("SELECT extversion FROM pg_extension WHERE extname='vector'"); out["pgvector"] = cur.fetchone()[0]
    (HERE / "hnsw.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    cur.execute("DROP TABLE items")


if __name__ == "__main__":
    main()
