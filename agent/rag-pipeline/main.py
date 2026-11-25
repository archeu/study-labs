"""页面上的两个实测：
1. 闭卷和 RAG 各问 3 个问题，看模型不给资料时答成什么样；
2. 30 个问题只走向量检索，正确的片排第几：前 k 片命中率随 k 怎么变，k 片资料让提示变多长。
结果写到 hitk.json（页面实验台用的就是它）。
uv run python -W ignore rag-pipeline/main.py
"""
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "pano-06-runbook-to-report"))
import rag_min as rag  # noqa: E402
from questions import QUESTIONS, first_hit_rank  # noqa: E402

PICK = ["Q15", "Q22", "Q13"]


def ntok(text):
    return len(rag.post(rag.GEN + "/tokenize", {"content": text})["tokens"])


def main():
    t0 = time.perf_counter()
    chunks = rag.load_chunks()
    M = rag.build_index(chunks)
    print(f"索引：{len(chunks)} 片，{M.shape[1]} 维，{time.perf_counter() - t0:.1f}s（含解析和 OCR）")

    print("\n=== 1. 闭卷 vs RAG（前 3 片） ===")
    timings = []
    for qid, q, gold, kind, ref in [x for x in QUESTIONS if x[0] in PICK]:
        cb, _ = rag.ask(q, closed_book=True)
        hits = rag.retrieve(q, chunks, M, k=3)
        ans, tm = rag.generate(rag.build_messages(q, hits))
        timings.append(tm)
        print(f"\n{qid} {q}\n  标准答案：{ref}\n  闭卷：{cb}")
        print(f"  检索：{[(c.section.split(' › ')[-1][:14], round(s, 3)) for c, s in hits]}\n  RAG：{ans}")
        ans2, hits2 = rag.ask(q, chunks, M, k=3, use_rerank=True)
        print(f"  先取 10 再重排留 3：{[(c.section.split(' › ')[-1][:14], s) for c, s in hits2]}\n  RAG+重排：{ans2}")

    print("\n=== 2. 只走向量检索，正确的片排第几 ===")
    ranks = {}
    for qid, q, gold, kind, ref in QUESTIONS:
        hits = rag.retrieve(q, chunks, M, k=len(chunks))
        rows = [{"doc_key": c.doc_key, "section": c.section} for c, _ in hits]
        ranks[qid] = first_hit_rank(rows, gold)
    hit = [round(sum(1 for r in ranks.values() if r and r <= k) / len(ranks), 3) for k in range(1, 11)]
    print("前 k 片命中率：" + " ".join(f"k={k}:{h:.2f}" for k, h in enumerate(hit, 1)))
    by_kind = {}
    for qid, q, gold, kind, ref in QUESTIONS:
        by_kind.setdefault(kind, []).append(ranks[qid])
    for kind, rs in by_kind.items():
        print(f"  {kind:8s} {len(rs)} 题，排名 {rs}")

    toks = [ntok(c.text) for c in chunks]
    base = ntok(rag.build_messages("x" * 20, [])[0]["content"]) + 40   # 规则 + 问题 + 模板的大约开销
    pre = [t["prompt_per_second"] for t in timings if t.get("prompt_per_second")]
    dec = [t["predicted_per_second"] for t in timings if t.get("predicted_per_second")]
    out = {"n_chunks": len(chunks), "hit_at_k": hit, "ranks": ranks, "avg_chunk_tokens": round(sum(toks) / len(toks), 1),
           "max_chunk_tokens": max(toks), "base_tokens": base,
           "prefill_tps": round(sum(pre) / len(pre), 1), "decode_tps": round(sum(dec) / len(dec), 1),
           "kinds": {q[0]: q[3] for q in QUESTIONS}}
    print(f"每片平均 {out['avg_chunk_tokens']} token（最长 {out['max_chunk_tokens']}）；"
          f"本机读提示 {out['prefill_tps']} token/s，生成 {out['decode_tps']} token/s")
    (HERE / "hitk.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
