"""全景那次真实链路：手册 v3.2 和故障单入库，再为 INC-20261109-02 写复盘报告的“根因与处置”一节。

先准备：corpus.py 生成 data/；三个 llama-server 和 pgvector 容器（见 README）。
uv run python pano-06-runbook-to-report/main.py            # 从空库开始跑完整个场景（kb-aware 计划）
uv run python pano-06-runbook-to-report/main.py naive      # 只说要写什么，看模型漏查了什么
uv run python pano-06-runbook-to-report/main.py manual     # 人写的 4 条检索语句，看“问对了”时的结果
输出每站耗时和数量，完整结果写到 runs/pano-run-<计划>.json。
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kb  # noqa: E402

TASK = ("写 INC-20261109-02 复盘报告的“根因与处置”一节，150～250 字：根因是什么，违反了运维手册哪条规定，"
        "当时用什么办法止血，为什么没有按手册回滚。")
PLAN_PROMPTS = {
    # 只说要写什么：1.7B 模型列出的 4 条全在问故障单本身
    "naive": ("你要写故障 INC-20261109-02（2026-11-09 10:42 order-svc P99 1.6 秒）的复盘报告“根因与处置”一节。"
              "动笔前要去知识库查资料。列出 4 条检索语句，每条一句话，各查一件事。"),
    # 再告诉它知识库里有什么、这一节要回答哪几问：相当于把检索工具的说明写清楚
    "kb-aware": ("你要写故障 INC-20261109-02（2026-11-09 10:42 order-svc P99 1.6 秒）的复盘报告“根因与处置”一节。"
                 "知识库里有：order-svc 运维手册（告警阈值、故障处置、发布与回滚规定、本地缓存规范、降级开关）、"
                 "历史故障单、学习笔记。这一节要回答四问：根因是什么；违反了手册哪条规定；用什么办法止血；"
                 "为什么没有回滚。每问写一条检索语句，用手册和故障单里会出现的词，不要写故障编号。"),
}
TEAMS = ("trade", "public")


# 对照：人按手册的用词写的 4 条，看检索和生成在“问对了”时能做到什么样
MANUAL = ["OrderDetailCache 本地缓存没设上限的根因", "本地缓存必须设置 maximumSize 和过期时间的规定",
          "关闭本地缓存的降级开关", "发布后多久内必须回滚，超过时间怎么办"]


def plan_queries(prompt):
    """站 6：让模型决定查什么。这里用一次结构化输出代替完整的 agent 循环。"""
    schema = {"type": "object", "properties": {"queries": {"type": "array", "items": {"type": "string"},
                                                             "minItems": 4, "maxItems": 4}},
              "required": ["queries"]}
    body = {"messages": [{"role": "user", "content": prompt}], "temperature": 0, "max_tokens": 300,
            "chat_template_kwargs": {"enable_thinking": False},
            "response_format": {"type": "json_schema", "json_schema": {"name": "plan", "schema": schema}}}
    r = kb.post(kb.GEN_URL + "/v1/chat/completions", body)
    return json.loads(r["choices"][0]["message"]["content"])["queries"]


def main(plan="kb-aware"):
    conn = kb.connect()
    conn.execute("TRUNCATE docs, chunks")
    D = kb.DATA
    t = {}

    print("昨天的知识库：手册 v3.1、11-08 的故障单、3 篇笔记")
    kb.ingest(conn, [D / "runbook/order-svc-runbook-v3.1.pdf", D / "tickets/INC-20261108-01.docx",
                     *sorted((D / "notes").glob("*.md"))])

    print("\n09:30 上传：手册 v3.2、11-09 的故障单（站 1～5）")
    t0 = time.perf_counter()
    st = kb.ingest(conn, [D / "runbook/order-svc-runbook-v3.2.pdf", D / "tickets/INC-20261109-02.docx",
                          D / "runbook/order-svc-runbook-v3.1.pdf"])  # v3.1 内容没变，会被跳过
    t["ingest_total_s"] = time.perf_counter() - t0
    t.update({k: v for k, v in st.items()})
    n = conn.execute("SELECT status, count(*) FROM chunks GROUP BY status ORDER BY 1").fetchall()
    print(f"  解析 {st['parse_s']:.1f}s（含 OCR）· 切块 {st['chunk_s']:.2f}s · 向量化 {st['embed_s']:.1f}s · "
          f"入库 {st['store_s']:.2f}s；库里各状态的片数：{dict(n)}")

    print("\n11:00 写复盘报告")
    t0 = time.perf_counter(); queries = MANUAL if plan == "manual" else plan_queries(PLAN_PROMPTS[plan]); t["plan_s"] = time.perf_counter() - t0
    print(f"站 6 模型决定查 {len(queries)} 件事（{t['plan_s']:.1f}s）")
    picked, per_query, all_top = {}, [], []
    t["retrieve_s"] = t["rerank_s"] = 0.0
    for q in queries:
        t0 = time.perf_counter(); fused, bm, vec = kb.hybrid(conn, q, TEAMS, k=20); t["retrieve_s"] += time.perf_counter() - t0
        t0 = time.perf_counter(); top = kb.rerank(q, fused[:20], top_n=3); t["rerank_s"] += time.perf_counter() - t0
        all_top += top
        per_query.append({"query": q, "bm25_top5": [r["id"] for r in bm[:5]], "vector_top5": [r["id"] for r in vec[:5]],
                          "rrf_top5": [r["id"] for r in fused[:5]], "rerank_top3": [(r["id"], r["rerank"]) for r in top]})
        print(f"  · {q}\n      BM25 {[r['id'].split('@')[1] if 'runbook' in r['id'] else r['id'] for r in bm[:3]]}"
              f"  向量 {[r['id'].split('@')[1] if 'runbook' in r['id'] else r['id'] for r in vec[:3]]}"
              f"\n      重排后 {[(r['id'], r['rerank']) for r in top]}")
        per_query[-1]["kept"] = [r["id"] for r in top if r["rerank"] > 0][:2]
    # 每条检索语句留重排分 > 0 的前 2 段，按名次轮流放：第 4 条语句的第 1 名不能被前 3 条的第 3 名挤掉
    for rank in range(2):
        for pq in per_query:
            if rank < len(pq["kept"]):
                rid = pq["kept"][rank]
                picked.setdefault(rid, next(r for r in all_top if r["id"] == rid))
    rows = list(picked.values())
    print(f"站 8 混合检索合计 {t['retrieve_s']:.1f}s · 站 9 重排合计 {t['rerank_s']:.1f}s · 送进生成的资料 {len(rows)} 段")

    t0 = time.perf_counter(); answer, usage, timings = kb.generate(TASK, rows); t["generate_s"] = time.perf_counter() - t0
    t["prompt_tokens"], t["completion_tokens"] = usage.get("prompt_tokens"), usage.get("completion_tokens")
    print(f"\n站 10 生成 {t['generate_s']:.1f}s（提示 {t['prompt_tokens']} token，输出 {t['completion_tokens']} token）\n")
    for i, r in enumerate(rows, 1):
        print(f"  [{i}] {kb.label(r)}  ← {r['id']}")
    print("\n" + answer + "\n")

    checks = kb.check_citations(answer, rows)
    cited = sum(1 for c in checks if c["cites"])
    weak = [c for c in checks if c["cites"] and c["cover"] < 0.5]
    print(f"站 11 引用回查：{len(checks)} 句，{cited} 句带引用，{len(weak)} 句的关键词在被引资料里不到一半")
    for c in checks:
        mark = "  " if c["cites"] and c["cover"] >= 0.5 else "⚠ "
        print(f"  {mark}{c['cover']:.2f} {c['cites']} {c['sentence'][:60]}")

    out = Path(__file__).resolve().parent / "runs"
    out.mkdir(exist_ok=True)
    (out / f"pano-run-{plan}.json").write_text(json.dumps({"timings": {k: round(v, 2) if isinstance(v, float) else v
                                                                for k, v in t.items()},
                                                    "queries": per_query, "sources": [kb.label(r) + " ← " + r["id"] for r in rows],
                                                    "answer": answer, "checks": checks}, ensure_ascii=False, indent=1))
    print("\n耗时（秒）：" + " · ".join(f"{k}={v:.1f}" for k, v in t.items() if k.endswith("_s")))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "kb-aware")
