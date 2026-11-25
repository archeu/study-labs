"""值班助手写一整份复盘报告（INC-20261109-02），每句话能点回原文。两种写法对比：

one   一次写完：五节要用的资料一次全给模型，“写一份五节的复盘报告”
outline 按提纲分节写：提纲写死在代码里（每节要查什么事先知道），每节单独检索、重排、生成；
      资料全局编号 S1、S2……；时间差这类数字由代码从时间线里算好，作为一条“计算事实”给模型
然后逐句回查：编号存不存在、是不是这一节给它的资料、句子里的关键词在被引资料里出现了多少。
需要 8091、8092、8093 三个 llama-server。
uv run python -W ignore report-generation/main.py        # 第一版：分节写 + 一次写完，本机约 6 分钟，写 report.json
uv run python -W ignore report-generation/main.py --v2   # 第二版：只用这次故障的单子、规则由代码判断、给引用格式示例
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
import importlib.util  # noqa: E402
_spec = importlib.util.spec_from_file_location("hybrid_main", HERE.parent / "hybrid-search" / "main.py")
hy = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(hy)

TITLE = "INC-20261109-02 复盘报告：order-svc P99 1.6 秒"
# 提纲：每节写什么、要查什么，事先就知道（Agentic RAG 页的结论：形状已知就让代码决定查什么）
OUTLINE = [
    ("概要", "两三句话：什么时候、哪个服务、什么现象、多久恢复。",
     ["INC-20261109-02 级别 开始 恢复 处理人"]),
    ("时间线", "按时间先后，每个关键时刻一句。",
     ["INC-20261109-02 时间线"]),
    ("根因", "说清根因是什么，违反了运维手册哪条规定，规定原文怎么写。",
     ["OrderDetailCache 根因", "本地缓存必须设置 maximumSize 和过期时间的规定"]),
    ("处置是否符合手册", "对照手册判断当时的处置：为什么没有回滚，用的降级开关是不是手册里写的。",
     ["发布后多久内必须回滚，超过时间怎么办", "关闭本地缓存的降级开关", "INC-20261109-02 时间线"]),
    ("改进项", "列出改进项，每条说明依据。",
     ["OrderDetailCache 修复和改进", "本地缓存容量按堆的 10% 估算 recordStats 命中率"]),
]
V2 = "--v2" in sys.argv
INCIDENT = "INC-20261109-02"
RULE_V2_EXAMPLE = "格式示例：10:42 收到 order-04 P99 告警[S2]。超过 30 分钟的问题优先用降级开关[S7][S9]。"
RULE = ("你在写故障复盘报告的一节。只能用下面编号的资料，每句话的句号前写出处，比如 [S3]，一句可以有多个编号；"
        "资料里没有的写“资料未提及”，不要推测，不要写资料以外的数字。用中文，三到五句，不要标题，不要客套话。")


def rule_check(mins_release_to_alert):
    """代码对照手册 §4.2 判断该不该回滚：规则是确定的，就别让模型去推。"""
    if mins_release_to_alert <= 30:
        return f"（由时间线和手册 §4.2 判断）告警时距发布 {mins_release_to_alert} 分钟，在 30 分钟回滚窗口内，按 §4.2 应立即回滚。"
    return f"（由时间线和手册 §4.2 判断）告警时距发布 {mins_release_to_alert} 分钟，已超过 30 分钟回滚窗口，按 §4.2 应优先用降级开关止血，而不是回滚。"


def facts_from_timeline(text):
    """代码从时间线里算时间差，不让模型算。"""
    t = dict(re.findall(r"(\d{2}:\d{2}) \| ([^\n]+)", text))
    def mins(a, b):
        ha, ma = map(int, a.split(":")); hb, mb = map(int, b.split(":"))
        return (hb * 60 + mb) - (ha * 60 + ma)
    rel = next(k for k, v in t.items() if "发布" in v)
    alert = next(k for k, v in t.items() if "告警" in v)
    rec = next(k for k, v in t.items() if "回到" in v)
    base = (f"（由时间线计算）{rel} 发布到 {alert} 告警相隔 {mins(rel, alert)} 分钟；"
            f"{alert} 告警到 {rec} 恢复相隔 {mins(alert, rec)} 分钟。")
    return (base, rule_check(mins(rel, alert))) if V2 else (base, None)


class Sources:
    """全局资料表：同一片只编一个号，按第一次用到的顺序编。"""

    def __init__(self):
        self.items, self.by_key = [], {}

    def add(self, key, label, text):
        if key not in self.by_key:
            self.items.append({"id": f"S{len(self.items) + 1}", "key": key, "label": label, "text": text})
            self.by_key[key] = self.items[-1]["id"]
        return self.by_key[key]

    def get(self, sid):
        return next(x for x in self.items if x["id"] == sid)


def label_of(row):
    sec = row["section"].split(" › ")[-1]
    if row["doc_key"] == "order-svc-runbook":
        return f"运维手册 v3.2 · {sec} · 第 {row['page']} 页"
    return f"{row['doc_key']} · {sec}"


def chat(system, user, max_tokens):
    t = time.perf_counter()
    r = rag.post(rag.GEN + "/v1/chat/completions", {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                                                   "temperature": 0, "max_tokens": max_tokens,
                                                   "chat_template_kwargs": {"enable_thinking": False}})
    return r["choices"][0]["message"]["content"].strip(), time.perf_counter() - t, r.get("usage", {})


def check(text, allowed, src):
    """逐句回查：有没有编号、编号存在吗、是这一节的资料吗、关键词覆盖多少。"""
    out = []
    for s in re.split(r"(?<=[。！？])|\n", text):
        s = s.strip(" -*")
        if len(s) < 6:
            continue
        ids = re.findall(r"\[(S\d+|F\d+)\]", s)
        ids = list(dict.fromkeys(ids))
        exist = [i for i in ids if i in allowed]
        body = re.sub(r"\[[SF]\d+\]", "", s)
        words = {w for w in kb.tokenize(body) if len(w) > 1}
        ref = " ".join(src[i].lower() for i in exist)
        cover = round(sum(w in ref for w in words) / max(1, len(words)), 2)
        out.append({"s": s, "ids": ids, "bad": [i for i in ids if i not in allowed], "cover": cover,
                    "flag": (not ids) or bool([i for i in ids if i not in allowed]) or cover < 0.5})
    return out


def main():
    chunks = rag.load_chunks()
    rows = [{"id": c.id, "doc_key": c.doc_key, "section": c.section, "page": c.page, "text": c.text} for c in chunks]
    M = rag.build_index(chunks)
    bm = kb.BM25(rows)
    src = Sources()
    fact_text, rule_text = facts_from_timeline(next(r["text"] for r in rows if r["doc_key"] == INCIDENT and "时间线" in r["section"]))
    if V2:   # 写的是哪次故障，事先知道：别的故障单一律不进候选（RAG 优化页机制 1）
        keep = [i for i, r in enumerate(rows) if not (r["doc_key"].startswith("INC-") and r["doc_key"] != INCIDENT)]
        rows = [rows[i] for i in keep]; M = M[keep]; bm = kb.BM25(rows)
    t_ret = 0.0
    plan = []
    for name, ask, queries in OUTLINE:
        sec_ids = []
        for q in queries:
            t0 = time.perf_counter()
            b = list(np.argsort(-np.asarray(hy.bm25_scores(bm, q)), kind="stable"))
            v = list(np.argsort(-(M @ rag.embed([f"Instruct: {kb.QUERY_TASK}\nQuery:{q}"])[0])))
            cand = hy.rrf([b[:20], v[:20]])[:10]
            rr = rag.post(rag.RERANK + "/v1/rerank", {"query": q, "documents": [rows[j]["section"] + "\n" + rows[j]["text"] for j in cand]})
            top = [cand[x["index"]] for x in sorted(rr["results"], key=lambda x: -x["relevance_score"]) if x["relevance_score"] > 0][:2]
            t_ret += time.perf_counter() - t0
            for j in top:
                sid = src.add(rows[j]["id"], label_of(rows[j]), rows[j]["text"])
                if sid not in sec_ids:
                    sec_ids.append(sid)
        plan.append((name, ask, queries, sec_ids))
    fid = src.add("FACT-1", "由时间线计算（代码算的，不是模型算的）", fact_text)
    rid = src.add("FACT-2", "由时间线和手册 §4.2 判断（代码判断的）", rule_text) if rule_text else None
    for p in plan:
        if p[0] in ("概要", "处置是否符合手册"):
            p[3].append(fid)
        if rid and p[0] == "处置是否符合手册":
            p[3].append(rid)
    text_of = {x["id"]: x["text"] for x in src.items}

    # 写法一：按提纲分节写
    sections, t_gen = [], 0.0
    for name, ask, queries, ids in plan:
        material = "\n\n".join(f"[{i}]（{src.get(i)['label']}）{text_of[i]}" for i in ids) or "（没有找到资料）"
        body, dt, usage = chat(RULE + (RULE_V2_EXAMPLE if V2 else ""), f"报告：{TITLE}\n本节：{name}。{ask}\n\n资料：\n{material}", 320)
        if V2:   # 只规范格式，不改内容：“[ S3 ]”→“[S3]”
            body = re.sub(r"\[\s*(S\d+)\s*\]", r"[\1]", body)
        t_gen += dt
        sections.append({"name": name, "queries": queries, "sources": ids, "text": body, "check": check(body, set(ids), text_of),
                         "sec": round(dt, 1), "prompt_tokens": usage.get("prompt_tokens")})
        print(f"\n## {name}（{dt:.0f}s，资料 {ids}）\n{body}")
    if V2:
        allck = [c for s_ in sections for c in s_["check"]]
        st = {"sentences": len(allck), "cited": sum(1 for c in allck if c["ids"]), "bad_ids": sum(1 for c in allck if c["bad"]),
              "flagged": sum(1 for c in allck if c["flag"])}
        old_ = json.loads((HERE / "report.json").read_text())
        old_["v2"] = {"sources": src.items, "fact": fact_text, "rule": rule_text, "sections": sections, "stats": st,
                      "time": {"retrieve_s": round(t_ret, 1), "gen_s": round(t_gen, 1)}}
        (HERE / "report.json").write_text(json.dumps(old_, ensure_ascii=False, indent=1))
        print("\n第二版：", st)
        return
    # 写法二：一次写完（同样的全部资料）
    allids = [x["id"] for x in src.items]
    material = "\n\n".join(f"[{i}]（{src.get(i)['label']}）{text_of[i]}" for i in allids)
    one, dt_one, usage_one = chat(RULE.replace("写故障复盘报告的一节", "写一份完整的故障复盘报告").replace("三到五句，不要标题", "分五节：概要、时间线、根因、处置是否符合手册、改进项，每节标题用“## ”开头"),
                                  f"报告：{TITLE}\n\n资料：\n{material}", 1200)
    one_check = check(one, set(allids), text_of)
    print(f"\n==== 一次写完（{dt_one:.0f}s，提示 {usage_one.get('prompt_tokens')} token）\n{one}")

    def stats(cks):
        n = len(cks)
        return {"sentences": n, "cited": sum(1 for c in cks if c["ids"]), "bad_ids": sum(1 for c in cks if c["bad"]),
                "flagged": sum(1 for c in cks if c["flag"])}
    allck = [c for s in sections for c in s["check"]]
    out = {"title": TITLE, "sources": src.items, "fact": fact_text, "sections": sections,
           "outline_stats": stats(allck), "one_text": one, "one_check": one_check, "one_stats": stats(one_check),
           "time": {"retrieve_s": round(t_ret, 1), "outline_gen_s": round(t_gen, 1), "one_gen_s": round(dt_one, 1),
                    "one_prompt_tokens": usage_one.get("prompt_tokens")}}
    print("\n分节写：", out["outline_stats"], " 一次写完：", out["one_stats"], " 用时：", out["time"])
    (HERE / "report.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
