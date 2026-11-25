"""站 06 的评测：30 个有答案的问题 + 3 个库里没有答案的问题，跑完整条检索 + 生成，再用三种“判官”看答案忠不忠实。

检索：BM25 + 向量 → RRF 前 20 → 重排，取前 3 片给模型（不设分数阈值，看模型自己会不会说“没有”）。
生成：Qwen3-1.7B，只根据资料回答、句末写 [编号]、没有就说“资料里没有”。
判官：
  1. 关键词覆盖：kb.check_citations，句子里的词在被引资料里出现了多少（全景第 11 站那个）
  2. 小模型当判官：同一个 Qwen3-1.7B，读资料和回答，输出 JSON：有没有资料里推不出的说法
  3. 人工：逐条对照资料核对，结果在 labels.json（score.py 读它）
需要 8091、8092、8093 三个 llama-server。
uv run python -W ignore rag-eval/main.py      # 本机约 25 分钟，写 answers.json
uv run python rag-eval/score.py               # 读 answers.json + labels.json，算指标，写 eval.json
"""
import json
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "pano-06-runbook-to-report"))
sys.path.insert(0, str(HERE.parent / "rag-pipeline"))
import kb  # noqa: E402
import rag_min as rag  # noqa: E402
from questions import NO_ANSWER, QUESTIONS, is_hit  # noqa: E402

TOP = 3
RULE = ("只根据下面的资料回答，每句话的句号前写出处编号，比如 [1]；资料里没有就只回答“资料里没有”，不要推测。"
        "用中文，一到三句话。")
JUDGE = ("你是审稿人。下面有编号的资料和一段回答。逐句检查回答：每一句的意思能不能由资料直接推出？"
         "数字、名字、时间要和资料一致。回答说“资料里没有”时，检查资料里是不是真的没有。")
SCHEMA = {"type": "object", "properties": {
    "supported": {"type": "boolean"},
    "unsupported": {"type": "array", "items": {"type": "string"}, "maxItems": 3}},
    "required": ["supported", "unsupported"]}


def chat(messages, max_tokens=220, schema=None):
    body = {"messages": messages, "temperature": 0, "max_tokens": max_tokens,
            "chat_template_kwargs": {"enable_thinking": False}}
    if schema:
        body["response_format"] = {"type": "json_schema", "json_schema": {"name": "j", "schema": schema}}
    t = time.perf_counter()
    r = rag.post(rag.GEN + "/v1/chat/completions", body)
    return r["choices"][0]["message"]["content"].strip(), time.perf_counter() - t


def sources(rows, idx):
    return "\n\n".join(f"[{i}] （{rows[j]['section'].split(' › ')[-1]}）{rows[j]['text']}" for i, j in enumerate(idx, start=1))


def main():
    import importlib.util  # hybrid-search/main.py 里的 rrf、bm25_scores（同名 main.py，按路径加载）
    spec = importlib.util.spec_from_file_location("hybrid_main", HERE.parent / "hybrid-search" / "main.py")
    hy = importlib.util.module_from_spec(spec); spec.loader.exec_module(hy)
    chunks = rag.load_chunks()
    rows = [{"id": c.id, "doc_key": c.doc_key, "section": c.section, "text": c.text} for c in chunks]
    M = rag.build_index(chunks)
    bm = kb.BM25(rows)
    items = [(q[0], q[1], q[2], q[4]) for q in QUESTIONS] + [(n[0], n[1], [], "（库里没有）") for n in NO_ANSWER]
    out = []
    t_all = time.perf_counter()
    for qid, q, gold, ref in items:
        t0 = time.perf_counter()
        b_order = list(np.argsort(-np.asarray(hy.bm25_scores(bm, q)), kind="stable"))
        qv = rag.embed([f"Instruct: {kb.QUERY_TASK}\nQuery:{q}"])[0]
        v_order = list(np.argsort(-(M @ qv)))
        cand = hy.rrf([b_order[:20], v_order[:20]])[:20]
        rr = rag.post(rag.RERANK + "/v1/rerank", {"query": q, "documents": [rows[j]["section"] + "\n" + rows[j]["text"] for j in cand]})
        sc = {cand[x["index"]]: x["relevance_score"] for x in rr["results"]}
        order = sorted(cand, key=lambda j: -sc[j])
        t_ret = time.perf_counter() - t0
        rank = next((i + 1 for i, j in enumerate(order) if is_hit(rows[j], gold)), None) if gold else None
        top = order[:TOP]
        src = sources(rows, top)
        ans, t_gen = chat([{"role": "system", "content": RULE}, {"role": "user", "content": f"资料：\n{src}\n\n问题：{q}"}])
        ctx_rows = [{"text": rows[j]["text"]} for j in top]
        lex = kb.check_citations(ans, ctx_rows)
        jtxt, t_judge = chat([{"role": "system", "content": JUDGE},
                              {"role": "user", "content": f"资料：\n{src}\n\n问题：{q}\n\n回答：{ans}"}], max_tokens=300, schema=SCHEMA)
        try:
            judge = json.loads(jtxt)
        except json.JSONDecodeError:
            judge = {"supported": None, "unsupported": [jtxt[:80]]}
        rec = {"qid": qid, "q": q, "ref": ref, "rank": rank, "top": [rows[j]["id"] for j in top],
               "top_sections": [rows[j]["section"].split(" › ")[-1] for j in top],
               "top_scores": [round(sc[j], 2) for j in top], "answer": ans, "lexical": lex,
               "lex_min_cover": min([c["cover"] for c in lex if c["cites"]], default=None),
               "lex_uncited": sum(1 for c in lex if not c["cites"]), "judge": judge,
               "t_retrieve": round(t_ret, 1), "t_gen": round(t_gen, 1), "t_judge": round(t_judge, 1)}
        out.append(rec)
        print(f"{qid} 排名 {rank!s:>4} 重排 {rec['top_scores']}  判官 {judge.get('supported')}  关键词最低覆盖 {rec['lex_min_cover']}")
        print(f"     问：{q}\n     答：{ans[:120]}")
        (HERE / "answers.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print(f"全部 {len(out)} 题用时 {time.perf_counter() - t_all:.0f} 秒")


if __name__ == "__main__":
    main()
