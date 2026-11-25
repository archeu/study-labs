"""混合检索页的实测：31 片、30 个问题，同一套片，比 6 种检索办法。

1. 只走 BM25（jieba 分词）  2. 只走向量  3. RRF 融合（k=60）  4. 分数归一化后加权（α=0.5）
5. RRF 前 20 再交给重排模型  6. BM25 的 b=0（不按长度归一）对照
另外：BM25 对一个问题逐词的得分拆解；jieba 怎么切开关名和版本号；每一步的耗时。
需要 8091（Embedding）和 8092（重排）两个 llama-server，见 pano-06-runbook-to-report/README.md。
uv run python -W ignore hybrid-search/main.py      # 本机约 7 分钟（重排 600 对），写 hybrid.json
"""
import json
import math
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "pano-06-runbook-to-report"))
sys.path.insert(0, str(HERE.parent / "rag-pipeline"))
import kb  # noqa: E402
import rag_min as rag  # noqa: E402
from questions import QUESTIONS, is_hit  # noqa: E402


def bm25_scores(bm, q):
    """对每一片算 BM25 分（kb.BM25 的公式，返回全部片的分数而不是前 k 个）。"""
    qt, n, out = kb.tokenize(q), len(bm.toks), []
    for toks in bm.toks:
        tf, s = Counter(toks), 0.0
        for w in qt:
            if tf[w]:
                idf = math.log(1 + (n - bm.df[w] + 0.5) / (bm.df[w] + 0.5))
                s += idf * tf[w] * (bm.k1 + 1) / (tf[w] + bm.k1 * (1 - bm.b + bm.b * len(toks) / bm.avg))
        out.append(s)
    return out


def ranks_of(order, rows, gold):
    return next((i + 1 for i, j in enumerate(order) if is_hit(rows[j], gold)), None)


def hits(rank_map, ks=(1, 3, 5)):
    n = len(rank_map)
    out = {k: round(sum(1 for r in rank_map.values() if r and r <= k) / n, 3) for k in ks}
    out["mrr"] = round(sum(1 / r for r in rank_map.values() if r) / n, 3)
    return out


def rrf(orders, k=60):
    score = Counter()
    for order in orders:
        for rank, j in enumerate(order, start=1):
            score[j] += 1 / (k + rank)
    return [j for j, _ in score.most_common()]


def weighted(scores_a, scores_b, alpha=0.5):
    def mm(x):
        x = np.asarray(x, float)
        return (x - x.min()) / (x.max() - x.min() + 1e-9)
    s = alpha * mm(scores_a) + (1 - alpha) * mm(scores_b)
    return list(np.argsort(-s))


def main():
    chunks = rag.load_chunks()
    rows = [{"id": c.id, "doc_key": c.doc_key, "section": c.section, "text": c.text} for c in chunks]
    t0 = time.perf_counter(); M = rag.build_index(chunks); t_index = time.perf_counter() - t0
    bm = kb.BM25(rows)
    bm_b0 = kb.BM25(rows, b=0.0)
    out = {"n_chunks": len(rows), "ids": [r["id"] for r in rows],
           "labels": [r["section"].split(" › ")[-1][:16] for r in rows], "per_q": {}}
    R = {k: {} for k in ("bm25", "bm25_b0", "vector", "rrf", "weighted", "rerank")}
    t = Counter()
    for qid, q, gold, kind, ref in QUESTIONS:
        n = len(rows)
        t0 = time.perf_counter()
        bscores = bm25_scores(bm, q)
        b_order = list(np.argsort(-np.asarray(bscores), kind="stable"))
        t["bm25_ms"] += (time.perf_counter() - t0) * 1000
        b0_order = list(np.argsort(-np.asarray(bm25_scores(bm_b0, q)), kind="stable"))
        t0 = time.perf_counter()
        qv = rag.embed([f"Instruct: {kb.QUERY_TASK}\nQuery:{q}"])[0]
        vscores = M @ qv
        v_order = list(np.argsort(-vscores))
        t["vector_ms"] += (time.perf_counter() - t0) * 1000
        f_order = rrf([b_order[:20], v_order[:20]])
        w_order = weighted(bscores, vscores)
        t0 = time.perf_counter()
        cand = f_order[:20]
        rr = rag.post(rag.RERANK + "/v1/rerank", {"query": q, "documents": [rows[j]["section"] + "\n" + rows[j]["text"] for j in cand]})
        rscore = {cand[x["index"]]: x["relevance_score"] for x in rr["results"]}
        r_order = sorted(cand, key=lambda j: -rscore[j])
        t["rerank_ms"] += (time.perf_counter() - t0) * 1000
        for key, order in (("bm25", b_order), ("bm25_b0", b0_order), ("vector", v_order), ("rrf", f_order),
                           ("weighted", w_order), ("rerank", r_order)):
            R[key][qid] = ranks_of(order, rows, gold)
        out["per_q"][qid] = {"kind": kind, "q": q, "gold": [j for j in range(n) if is_hit(rows[j], gold)],
                             "bm25": [int(j) for j in b_order], "vector": [int(j) for j in v_order],
                             "bm25_score": [round(float(bscores[j]), 2) for j in b_order[:5]],
                             "vec_score": [round(float(vscores[j]), 3) for j in v_order[:5]],
                             "rerank": [int(j) for j in r_order[:5]], "rerank_score": [round(rscore[j], 2) for j in r_order[:5]]}
        print(f"{qid} {kind:7s} BM25 {R['bm25'][qid]!s:>4} 向量 {R['vector'][qid]!s:>4} RRF {R['rrf'][qid]!s:>4} "
              f"加权 {R['weighted'][qid]!s:>4} 重排 {R['rerank'][qid]!s:>4}  {q}")
    out["ranks"] = R
    out["hits"] = {k: hits(v) for k, v in R.items()}
    nq = len(QUESTIONS)
    out["timing"] = {"index_s": round(t_index, 1), "bm25_ms": round(t["bm25_ms"] / nq, 2),
                     "vector_ms": round(t["vector_ms"] / nq, 1), "rerank_ms": round(t["rerank_ms"] / nq)}
    print()
    for k, v in out["hits"].items():
        print(f"{k:9s} @1 {v[1]:.2f}  @3 {v[3]:.2f}  @5 {v[5]:.2f}  MRR {v['mrr']:.3f}")
    print("每个问题平均耗时：", out["timing"])
    by = {}
    for qid, q, gold, kind, ref in QUESTIONS:
        for k in ("bm25", "vector", "rrf", "rerank"):
            by.setdefault(kind, {}).setdefault(k, []).append(R[k][qid])
    out["by_kind"] = {kind: {k: round(sum(1 for r in v if r == 1) / len(v), 2) for k, v in d.items()} for kind, d in by.items()}
    print("按题型，前 1 片命中：", out["by_kind"])

    # BM25 逐词拆解：一个带开关名的问题
    q = "想临时停用本地缓存，改哪个开关？"
    qt = kb.tokenize(q)
    j = next(i for i, r in enumerate(rows) if "附录" in r["section"])
    toks = bm.toks[j]; tf = Counter(toks)
    parts = []
    for w in qt:
        df = bm.df[w]
        idf = math.log(1 + (len(rows) - df + 0.5) / (df + 0.5)) if df else 0
        s = idf * tf[w] * (bm.k1 + 1) / (tf[w] + bm.k1 * (1 - bm.b + bm.b * len(toks) / bm.avg)) if tf[w] else 0
        parts.append({"w": w, "df": df, "idf": round(idf, 2), "tf": tf[w], "score": round(s, 2)})
    out["bm25_explain"] = {"q": q, "doc": rows[j]["id"], "len": len(toks), "avg": round(bm.avg, 1), "parts": parts}
    print("BM25 拆解（附录 C 那片）：", parts, "片长", len(toks), "平均", round(bm.avg, 1))
    out["jieba"] = {s: kb.tokenize(s) for s in ["order.cache.local.enabled", "maximumSize(50000)", "v3.2", "-XX:MaxGCPauseMillis",
                                              "INC-20261109-02", "本地缓存必须设置容量上限", "停用本地缓存"]}
    for s_, toks_ in out["jieba"].items():
        print(f"jieba：{s_!r} → {toks_}")
    (HERE / "hybrid.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
