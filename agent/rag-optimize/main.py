"""RAG 优化三招的本机实测，都用站 06 的评测集量：
filter   元数据过滤：旧版手册 v3.1 没下线时会抢到前面吗？按 status 过滤；问题里有日期时按故障日期过滤
parent   父子块：用一句一片的小片检索，命中后交给模型它所在的整节
rewrite  查询改写：让模型把问题改写成文档里的说法；HyDE：让模型先写一段“假答案”再拿它去检索
fuse     多路查询：原问题、改写、假答案各检索一次，RRF 合并（用 rewrite 存下的文字，不再调模型）
需要 8091（Embedding）；rewrite 还要 8093（生成）。
uv run python -W ignore rag-optimize/main.py filter parent rewrite fuse   # 本机约 15 分钟，写 opt.json
"""
import json
import re
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "pano-06-runbook-to-report"))
sys.path.insert(0, str(HERE.parent / "rag-pipeline"))
import kb  # noqa: E402
import rag_min as rag  # noqa: E402
from questions import QUESTIONS, has_evidence, is_hit  # noqa: E402

import importlib.util  # hybrid-search/main.py 里的 bm25_scores、rrf
_spec = importlib.util.spec_from_file_location("hybrid_main", HERE.parent / "hybrid-search" / "main.py")
hy = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(hy)

OUT = HERE / "opt.json"


def qvec(text):
    return rag.embed([f"Instruct: {kb.QUERY_TASK}\nQuery:{text}"])[0]


def rows_of(chunks):
    return [{"id": c.id, "doc_key": c.doc_key, "version": c.version, "section": c.section, "page": c.page,
             "text": c.text} for c in chunks]


def hybrid_order(rows, bm, M, q, qv=None, bm_q=None):
    b = list(np.argsort(-np.asarray(hy.bm25_scores(bm, bm_q or q)), kind="stable"))
    v = list(np.argsort(-(M @ (qv if qv is not None else qvec(q)))))
    return hy.rrf([b[:20], v[:20]]), v


def rank(order, rows, gold, current=None):
    for i, j in enumerate(order, start=1):
        if is_hit(rows[j], gold) and (current is None or rows[j]["version"] == current.get(rows[j]["doc_key"], rows[j]["version"])):
            return i
    return None


def hits(ranks):
    n = len(ranks)
    return {"@1": round(sum(1 for r in ranks.values() if r and r <= 1) / n, 3),
            "@3": round(sum(1 for r in ranks.values() if r and r <= 3) / n, 3),
            "mrr": round(sum(1 / r for r in ranks.values() if r) / n, 3)}


def exp_filter(res):
    """v3.1 的 16 片也在库里（没下线）：只按相似度排，会不会排到前面。"""
    chunks = rag.load_chunks()
    d31 = kb.doc_of(kb.DATA / "runbook/order-svc-runbook-v3.1.pdf")
    old = kb.chunk(d31, kb.clean(kb.parse(d31)))
    allc = chunks + old
    rows = rows_of(allc)
    M = rag.build_index(allc)
    bm = kb.BM25(rows)
    cur = {"order-svc-runbook": "v3.2"}
    stale_top1, stale_top3, ex = [], [], {}
    r_all, r_cur = {}, {}
    keep = [i for i, r in enumerate(rows) if r["version"] != "v3.1"]
    for qid, q, gold, kind, ref in QUESTIONS:
        order, _ = hybrid_order(rows, bm, M, q)
        top3 = [rows[j] for j in order[:3]]
        if top3[0]["version"] == "v3.1":
            stale_top1.append(qid)
        if any(r["version"] == "v3.1" for r in top3):
            stale_top3.append(qid)
        if qid in ("Q01", "Q15", "Q13"):
            ex[qid] = [(r["version"], r["section"].split(" › ")[-1], r["text"][:60]) for r in top3]
        r_all[qid] = rank(order, rows, gold, cur)
        r_cur[qid] = rank([j for j in order if j in keep], rows, gold, cur)
    res["filter_version"] = {"n_chunks": len(rows), "stale_top1": stale_top1, "stale_top3": stale_top3, "examples": ex,
                             "hits_unfiltered": hits(r_all), "hits_filtered": hits(r_cur)}
    print(f"v3.1 没下线（{len(rows)} 片）：前 1 片是旧版的题 {stale_top1}；前 3 片里有旧版的题 {stale_top3}")
    print("  不过滤 v3.2 命中：", hits(r_all), " 只查 current：", hits(r_cur))
    for k, v in ex.items():
        print(f"  {k} 前 3：", v)

    # 问题里有日期：按故障单的开始日期过滤
    rows = rows_of(chunks); M = rag.build_index(chunks); bm = kb.BM25(rows)
    start = {}
    for name in ("tickets/INC-20261108-01.docx", "tickets/INC-20261109-02.docx"):
        d = kb.doc_of(kb.DATA / name)
        for b in kb.parse(d):
            m = re.search(r"开始 \| (\d{4})-(\d{2})-(\d{2})", b.text)
            if m:
                start[d.key] = (int(m.group(2)), int(m.group(3)))
    date_res = {}
    for qid, q, gold, kind, ref in QUESTIONS:
        m = re.search(r"(\d+) 月 (\d+) 日", q)
        if not m:
            continue
        day = (int(m.group(1)), int(m.group(2)))
        order, _ = hybrid_order(rows, bm, M, q)
        keep = [j for j in order if start.get(rows[j]["doc_key"]) == day]
        date_res[qid] = {"q": q, "date": f"{day[0]}-{day[1]}", "before": rank(order, rows, gold), "after": rank(keep, rows, gold),
                         "kept": len(keep)}
        print(f"  {qid} 日期 {day}：过滤前排第 {date_res[qid]['before']}，只留那天的故障单（{len(keep)} 片）后第 {date_res[qid]['after']}")
    res["filter_date"] = date_res


def exp_parent(res):
    """父子块：每节按句子拆成小片（子），检索子，交给模型父（整节）。"""
    chunks = rag.load_chunks()
    rows = rows_of(chunks)
    kids, parent = [], []
    for j, c in enumerate(chunks):
        for s in re.split(r"(?<=[。；\n])", c.text):
            s = s.strip()
            if len(s) >= 8:
                kids.append(f"{c.doc_key} {c.version} › {c.section}\n{s}")
                parent.append(j)
    t0 = time.perf_counter(); K = rag.embed(kids); t_kid = time.perf_counter() - t0
    M = rag.build_index(chunks)
    toks_parent = [len(rag.post(kb.EMBED_URL + "/tokenize", {"content": c.text})["tokens"]) for c in chunks]
    toks_kid = [len(rag.post(kb.EMBED_URL + "/tokenize", {"content": k.split("\n", 1)[1]})["tokens"]) for k in kids]
    r_sec, r_par, r_kid_ev = {}, {}, {}
    for qid, q, gold, kind, ref in QUESTIONS:
        qv = qvec(q)
        r_sec[qid] = rank(list(np.argsort(-(M @ qv))), rows, gold)
        korder = list(np.argsort(-(K @ qv)))
        seen, porder = set(), []
        for k in korder:
            if parent[k] not in seen:
                seen.add(parent[k]); porder.append(parent[k])
        r_par[qid] = rank(porder, rows, gold)
        r_kid_ev[qid] = next((i + 1 for i, k in enumerate(korder) if has_evidence(kids[k], qid)), None)
    res["parent"] = {"n_parent": len(chunks), "n_kid": len(kids), "embed_kids_s": round(t_kid, 1),
                     "avg_tok_parent": round(sum(toks_parent) / len(toks_parent), 1),
                     "avg_tok_kid": round(sum(toks_kid) / len(toks_kid), 1),
                     "hits_section": hits(r_sec), "hits_parent": hits(r_par), "hits_kid_evidence": hits(r_kid_ev),
                     "ranks_section": r_sec, "ranks_parent": r_par}
    print(f"父子块：{len(chunks)} 个父（整节，平均 {res['parent']['avg_tok_parent']} token），{len(kids)} 个子（一句，平均 {res['parent']['avg_tok_kid']} token）")
    print("  按整节检索：", hits(r_sec), " 按句检索再换成整节：", hits(r_par), " 只拿那一句（证据在不在）：", hits(r_kid_ev))
    diff = {q: (r_sec[q], r_par[q]) for q in r_sec if r_sec[q] != r_par[q]}
    print("  名次变了的题：", diff)
    res["parent"]["diff"] = diff


REWRITE = ("把用户的问题改写成一条用来检索 order-svc 运维手册和故障单的检索语句：用文档里可能出现的书面说法，"
           "保留服务名、接口编号、开关名、数字和日期，去掉口语和疑问词。只输出检索语句，不要解释。")
HYDE = "假设你在写 order-svc 运维手册，写一两句话直接回答下面的问题，像手册原文那样写，不超过 60 字。只输出这段话。"


def gen(system, user, max_tokens=120):
    r = rag.post(rag.GEN + "/v1/chat/completions", {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                                                   "temperature": 0, "max_tokens": max_tokens,
                                                   "chat_template_kwargs": {"enable_thinking": False}})
    return r["choices"][0]["message"]["content"].strip()


def exp_rewrite(res):
    chunks = rag.load_chunks()
    rows = rows_of(chunks); M = rag.build_index(chunks); bm = kb.BM25(rows)
    R = {k: {} for k in ("base_vec", "base_hyb", "rw_vec", "rw_hyb", "hyde_vec", "hyde_hyb")}
    texts = {}
    t = {"rw": 0.0, "hyde": 0.0}
    for qid, q, gold, kind, ref in QUESTIONS:
        order, v = hybrid_order(rows, bm, M, q)
        R["base_vec"][qid], R["base_hyb"][qid] = rank(v, rows, gold), rank(order, rows, gold)
        t0 = time.perf_counter(); rw = gen(REWRITE, q); t["rw"] += time.perf_counter() - t0
        order, v = hybrid_order(rows, bm, M, rw)
        R["rw_vec"][qid], R["rw_hyb"][qid] = rank(v, rows, gold), rank(order, rows, gold)
        t0 = time.perf_counter(); hyp = gen(HYDE, q); t["hyde"] += time.perf_counter() - t0
        hv = rag.embed([hyp])[0]                     # 假答案当“文档”算向量，不加问题的指令
        order, v = hybrid_order(rows, bm, M, q, qv=hv, bm_q=q + " " + hyp)
        R["hyde_vec"][qid], R["hyde_hyb"][qid] = rank(v, rows, gold), rank(order, rows, gold)
        texts[qid] = {"q": q, "rewrite": rw, "hyde": hyp}
        print(f"{qid} 原问 {R['base_hyb'][qid]!s:>3} 改写 {R['rw_hyb'][qid]!s:>3} HyDE {R['hyde_hyb'][qid]!s:>3} ｜ 改写：{rw[:40]} ｜ HyDE：{hyp[:40]}")
    res["rewrite"] = {"hits": {k: hits(v) for k, v in R.items()}, "ranks": R, "texts": texts,
                      "sec_per_q": {k: round(v / len(QUESTIONS), 1) for k, v in t.items()}}
    for k, v in res["rewrite"]["hits"].items():
        print(f"  {k:9s} {v}")
    print("  每题多花（秒）：", res["rewrite"]["sec_per_q"])


def exp_fuse(res):
    """不再调模型：拿 rewrite 那一步存下的改写和假答案，和原问题各检索一次，再用 RRF 合并（多路查询）。"""
    chunks = rag.load_chunks()
    rows = rows_of(chunks); M = rag.build_index(chunks); bm = kb.BM25(rows)
    T = res["rewrite"]["texts"]
    R = {"orig+rw": {}, "orig+hyde": {}, "orig+rw+hyde": {}}
    for qid, q, gold, kind, ref in QUESTIONS:
        o, _ = hybrid_order(rows, bm, M, q)
        w, _ = hybrid_order(rows, bm, M, T[qid]["rewrite"])
        h, _ = hybrid_order(rows, bm, M, q, qv=rag.embed([T[qid]["hyde"]])[0], bm_q=q + " " + T[qid]["hyde"])
        R["orig+rw"][qid] = rank(hy.rrf([o[:20], w[:20]]), rows, gold)
        R["orig+hyde"][qid] = rank(hy.rrf([o[:20], h[:20]]), rows, gold)
        R["orig+rw+hyde"][qid] = rank(hy.rrf([o[:20], w[:20], h[:20]]), rows, gold)
    res["rewrite"]["fused"] = {k: hits(v) for k, v in R.items()}
    res["rewrite"]["fused_ranks"] = R
    for k, v in res["rewrite"]["fused"].items():
        print(f"  {k:13s} {v}")


def main():
    res = json.loads(OUT.read_text()) if OUT.exists() else {}
    for name in sys.argv[1:]:
        {"filter": exp_filter, "parent": exp_parent, "rewrite": exp_rewrite, "fuse": exp_fuse}[name](res)
        OUT.write_text(json.dumps(res, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
