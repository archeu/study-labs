"""最小 RAG：切块 → 向量化 → 检索 →（重排）→ 拼提示 → 生成，每步十来行。

向量放在内存里暴力算余弦，不用数据库；切块直接用全景那份代码（kb.py），切块的讲究见“清洗、切块策略和元数据”那页。
uv run python rag-pipeline/rag_min.py "想临时停用本地缓存，改哪个开关？"
uv run python rag-pipeline/rag_min.py --closed-book "想临时停用本地缓存，改哪个开关？"   # 不给资料，直接问
uv run python rag-pipeline/rag_min.py --rerank "..."                                    # 先多取 10 段，重排后留 3 段
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-06-runbook-to-report"))
import kb  # noqa: E402

EMBED, RERANK, GEN = kb.EMBED_URL, kb.RERANK_URL, kb.GEN_URL
DOCS = ["runbook/order-svc-runbook-v3.2.pdf", "tickets/INC-20261108-01.docx", "tickets/INC-20261109-02.docx",
        "notes/caffeine-local-cache.md", "notes/g1-old-gen.md", "notes/hikari-pool.md"]


def post(url, body):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read())


# ① 切块（索引阶段，资料不变就只做一次）
def load_chunks():
    chunks = []
    for name in DOCS:
        d = kb.doc_of(kb.DATA / name)
        chunks += kb.chunk(d, kb.clean(kb.parse(d)))
    return chunks


# ② 向量化：每片一个 1024 维向量；服务端已经归一化，点积就是余弦相似度
def embed(texts):
    r = post(EMBED + "/v1/embeddings", {"input": texts})
    return np.array([x["embedding"] for x in sorted(r["data"], key=lambda x: x["index"])])


def build_index(chunks):
    return embed([c.embed_text() for c in chunks])        # 形状 (片数, 1024)


# ③ 检索：问题也变成向量（Qwen3-Embedding 的问题要带一句任务说明），和每一片比，取最像的 k 片
def retrieve(q, chunks, M, k=3):
    qv = embed([f"Instruct: {kb.QUERY_TASK}\nQuery:{q}"])[0]
    sims = M @ qv
    top = np.argsort(-sims)[:k]
    return [(chunks[i], float(sims[i])) for i in top]


# ③' 重排（可选）：问题和每一段放在一起读，重新打分
def rerank(q, hits, k=3):
    r = post(RERANK + "/v1/rerank", {"query": q, "documents": [c.text for c, _ in hits]})
    order = sorted(r["results"], key=lambda x: -x["relevance_score"])[:k]
    return [(hits[x["index"]][0], round(x["relevance_score"], 3)) for x in order]


# ④ 拼提示：编号的资料 + 规则 + 问题
def build_messages(q, hits):
    if hits is None:   # 闭卷：不给资料
        return [{"role": "user", "content": q + "\n用中文简短回答。"}]
    src = "\n\n".join(f"[{i}] （{c.doc_key} {c.version} {c.section.split(' › ')[-1]}）{c.text}"
                      for i, (c, _) in enumerate(hits, start=1))
    rule = "只根据下面的资料回答，句末写出处编号，比如 [1]；资料里没有就说“资料里没有”。用中文简短回答。"
    return [{"role": "system", "content": rule}, {"role": "user", "content": f"资料：\n{src}\n\n问题：{q}"}]


# ⑤ 生成
def generate(messages, max_tokens=200):
    r = post(GEN + "/v1/chat/completions", {"messages": messages, "temperature": 0, "max_tokens": max_tokens,
                                            "chat_template_kwargs": {"enable_thinking": False}})
    return r["choices"][0]["message"]["content"].strip(), r.get("timings", {})


def ask(q, chunks=None, M=None, k=3, closed_book=False, use_rerank=False):
    if closed_book:
        return generate(build_messages(q, None))[0], []
    hits = retrieve(q, chunks, M, k=10 if use_rerank else k)
    if use_rerank:
        hits = rerank(q, hits, k)
    return generate(build_messages(q, hits))[0], hits


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    q = args[0] if args else "想临时停用本地缓存，改哪个开关？"
    if "--closed-book" in sys.argv:
        print(ask(q, closed_book=True)[0])
        sys.exit()
    chunks = load_chunks()
    M = build_index(chunks)
    answer, hits = ask(q, chunks, M, use_rerank="--rerank" in sys.argv)
    for c, s in hits:
        print(f"  {s:.3f}  {c.id}  {c.section.split(' › ')[-1]}")
    print(answer)
