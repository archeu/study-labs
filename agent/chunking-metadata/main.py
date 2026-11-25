"""同一批资料、同一个 Embedding 模型、同 30 个问题，换 7 种清洗 / 切块 / 元数据的做法，看检索命中率。

命中按“证据”算：答案所在那句话的一段原文完整地落在某一片里（questions.EVIDENCE）。
固定长度切块没有章节，只能这样评；证据被切断在两片之间，也就算没命中。
需要 8091 上的 Embedding 服务（见 pano-06-runbook-to-report/README.md）。
uv run python -W ignore chunking-metadata/main.py     # 约 5 分钟，结果写 chunks.json
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
from questions import QUESTIONS, has_evidence  # noqa: E402

DOCS = rag.DOCS


def load(clean=True):
    out = []
    for name in DOCS:
        d = kb.doc_of(kb.DATA / name)
        bl = kb.parse(d)
        out.append((d, kb.clean(bl) if clean else bl))
    return out


def fixed(docs, size, overlap=0):
    """不管结构，每 size 个字切一刀；overlap 是相邻两片重叠的字数。"""
    res = []
    for d, bl in docs:
        text = "\n".join(b.text for b in bl)
        step = size - overlap
        for i in range(0, max(1, len(text) - overlap), step):
            res.append({"doc": d.key, "text": text[i:i + size], "embed": text[i:i + size]})
    return res


def by_page(docs):
    res = []
    for d, bl in docs:
        for p in sorted({b.page for b in bl}):
            t = "\n".join(b.text for b in bl if b.page == p)
            res.append({"doc": d.key, "text": t, "embed": t})
    return res


def ticket_meta(d, bl):
    """故障单的“开始”时间写进片的前缀：2026-11-09 10:42 → 2026 年 11 月 9 日。"""
    for b in bl:
        m = re.search(r"开始 \| (\d{4})-(\d{2})-(\d{2})", b.text)
        if m:
            return f"{int(m.group(1))} 年 {int(m.group(2))} 月 {int(m.group(3))} 日 order-svc 故障"
    return ""


def by_heading(docs, prefix=True, meta=False):
    res = []
    for d, bl in docs:
        extra = ticket_meta(d, bl) if meta else ""
        for c in kb.chunk(d, bl):
            emb = c.embed_text() if prefix else c.text
            if extra:
                emb = extra + "\n" + emb
            res.append({"doc": d.key, "text": c.text, "embed": emb, "section": c.section, "page": c.page,
                        "meta": {k: v for k, v in c.__dict__.items() if k not in ("vec", "text")}})
    return res


def ntok(texts):
    return [len(rag.post(kb.EMBED_URL + "/tokenize", {"content": t})["tokens"]) for t in texts]


def main():
    clean, raw = load(True), load(False)
    strategies = {
        "fixed200": ("固定 200 字", fixed(clean, 200)),
        "fixed200o50": ("固定 200 字，重叠 50", fixed(clean, 200, 50)),
        "page": ("一页一片", by_page(clean)),
        "heading_noclean": ("按标题，不清洗", by_heading(raw)),
        "heading_noprefix": ("按标题，片前不带标题路径", by_heading(clean, prefix=False)),
        "heading": ("按标题 + 标题路径（全景用的）", by_heading(clean)),
        "heading_meta": ("按标题 + 标题路径 + 故障日期", by_heading(clean, meta=True)),
    }
    qs = [q for q in QUESTIONS]
    t0 = time.perf_counter()
    Q = rag.embed([f"Instruct: {kb.QUERY_TASK}\nQuery:{q[1]}" for q in qs])
    out = {"strategies": {}}
    for key, (name, chunks) in strategies.items():
        t0 = time.perf_counter()
        M = rag.embed([c["embed"] for c in chunks])
        dt = time.perf_counter() - t0
        toks = ntok([c["embed"] for c in chunks])
        ranks, split = {}, []
        for (qid, *_), qv in zip(qs, Q):
            order = np.argsort(-(M @ qv))
            ranks[qid] = next((i + 1 for i, j in enumerate(order) if has_evidence(chunks[j]["text"], qid)), None)
            if ranks[qid] is None:
                split.append(qid)
        hit = {k: round(sum(1 for r in ranks.values() if r and r <= k) / len(qs), 3) for k in (1, 3, 5)}
        out["strategies"][key] = {"name": name, "n": len(chunks), "avg_tok": round(sum(toks) / len(toks), 1),
                                  "max_tok": max(toks), "hit": hit, "ranks": ranks, "not_in_any": split,
                                  "embed_s": round(dt, 1)}
        print(f"{name:24s} {len(chunks):3d} 片  平均 {out['strategies'][key]['avg_tok']:6.1f} token  "
              f"命中 @1 {hit[1]:.2f} @3 {hit[3]:.2f} @5 {hit[5]:.2f}  证据不完整在任何一片里：{split}")

    # 清洗前后：页眉、目录、连字
    hdr = "order-svc 运维手册 v3.2 · 交易组 · 内部资料"
    nc, cl = strategies["heading_noclean"][1], strategies["heading"][1]
    out["clean"] = {
        "header_in_chunks_raw": sum(hdr in c["text"] for c in nc), "header_in_chunks_clean": sum(hdr in c["text"] for c in cl),
        "toc_chunks_raw": sum(bool(re.search(r"\.{6,}", c["text"])) for c in nc),
        "caffeine_raw": sum("Caffeine" in c["text"] for c in nc), "caffeine_clean": sum("Caffeine" in c["text"] for c in cl),
        "ligature_raw": sum("ﬀ" in c["text"] for c in nc)}
    print("清洗：", out["clean"])
    # 一片的完整元数据
    sample = next(c for c in strategies["heading_meta"][1] if "4.3" in c.get("section", ""))
    out["sample"] = {"meta": sample["meta"], "embed": sample["embed"], "text": sample["text"]}
    tk = next(c for c in strategies["heading_meta"][1] if c["doc"] == "INC-20261108-01" and "根因" in c["section"])
    out["sample_ticket_embed"] = tk["embed"]
    print("一片的元数据：", json.dumps(sample["meta"], ensure_ascii=False))
    print("故障单那片算向量用的文字：", tk["embed"].replace("\n", " ⏎ "))
    # 固定长度切块时，“证据被切断”的一个例子
    f200 = strategies["fixed200"][1]
    for qid in out["strategies"]["fixed200"]["not_in_any"][:3]:
        ev = __import__("questions").EVIDENCE[qid]
        for a, b in zip(f200, f200[1:]):
            joined = a["text"] + b["text"]
            if a["doc"] == b["doc"] and has_evidence(joined, qid):
                out.setdefault("cut_examples", []).append([qid, ev, a["text"][-30:], b["text"][:30]])
                print(f"  {qid} 证据“{ev}”被切成 …{a['text'][-18:]!r} | {b['text'][:18]!r}…")
                break
    (HERE / "chunks.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
