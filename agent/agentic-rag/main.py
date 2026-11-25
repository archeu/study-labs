"""让 agent 自己决定查什么：4 道要查好几处的问题，三种做法各跑一遍。

A 只查一次：拿用户原话检索一次，前 4 片交给模型回答（前五页的做法）
B 先列计划再查：一次调用让模型列出要查的几件事（全景第 6 站的做法），每件事检索 2 片，合起来回答
C agent 循环：把检索做成工具 search_kb，模型自己决定查什么、查几次，最多 4 次，查够了自己回答
D 同 C，但第一轮用 tool_choice 逼它至少查一次（代码保底）
E 同 D，再打开 Qwen3 的思考模式
F 对照：人按题型写好检索计划（代码决定查什么），只让模型回答
模型都是本机 Qwen3-1.7B（llama-server 的 /v1/messages，用 Anthropic SDK 调），检索是 BM25 + 向量 + RRF。
uv run python -W ignore agentic-rag/main.py                 # A B C D 四种做法 × 4 题，本机约 12 分钟，写 runs.json
uv run python -W ignore agentic-rag/main.py E --only=A1,A2   # 打开思考再跑两题（每轮几十秒）
"""
import json
import os
import sys
import time
from pathlib import Path

import anthropic
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "pano-06-runbook-to-report"))
sys.path.insert(0, str(HERE.parent / "rag-pipeline"))
import kb  # noqa: E402
import rag_min as rag  # noqa: E402
import importlib.util  # noqa: E402
_spec = importlib.util.spec_from_file_location("hybrid_main", HERE.parent / "hybrid-search" / "main.py")
hy = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(hy)

MODEL = os.environ.get("MODEL", "qwen3-1.7b")
client = anthropic.Anthropic(base_url=os.environ.get("GEN_BASE", "http://127.0.0.1:8093"), api_key="local-no-key", timeout=600)
NOTHINK = {"chat_template_kwargs": {"enable_thinking": False}}
MAX_CALLS = 4

QUESTIONS = [
    ("A1", "INC-20261109-02 为什么没有按手册回滚，而是用了降级开关？",
     "发布 10:00、告警 10:42，已过 30 分钟；§4.2 规定超过 30 分钟优先用降级开关止血；用的是 order.cache.local.enabled",
     ["时间线", "4.2 回滚"]),
    ("A2", "11-09 那次故障违反了运维手册哪条规定？规定原文怎么写？",
     "违反 §4.3：本地缓存必须同时设置 maximumSize 和 expireAfterWrite；OrderDetailCache 两个都没设",
     ["根因", "4.3 本地缓存规范"]),
    ("A3", "11-08 和 11-09 两次 order-svc 故障，原因有什么共同点？",
     "都是一次性往堆里放了大量对象、Old 区被占满（41 万行导出 / 120 万条缓存），都来自新上线的功能",
     ["根因"]),
    ("A4", "关掉本地缓存的降级开关以后，订单详情改查哪里？多久生效？",
     "改查 Redis；配置中心修改，1 分钟内生效、不用重启（附录 C，单跳对照题）",
     ["附录"]),
]

SYSTEM = ("你是 order-svc 的值班助手。只根据检索到的资料回答，每句话后面写出处编号，比如 [S3]；资料里没有就说没有。用中文，三句话以内。")
TOOL = {"name": "search_kb",
        "description": ("在交易组知识库里检索，返回最相关的 3 段，每段带编号 [S1]、[S2]……。"
                        "知识库里有：order-svc 运维手册 v3.2（告警阈值、故障处置、发布与回滚、本地缓存规范、JVM 参数、附录 C 降级开关）、"
                        "故障单 INC-20261108-01 和 INC-20261109-02（时间线、根因、修复）、学习笔记。"
                        "一次只查一件事；问题涉及“规定”和“事件”两方面时，要分别查。"),
        "input_schema": {"type": "object", "properties": {"query": {"type": "string", "description": "检索语句，一件事"}},
                         "required": ["query"]}}


class KB:
    def __init__(self):
        self.chunks = rag.load_chunks()
        self.rows = [{"id": c.id, "doc_key": c.doc_key, "section": c.section, "text": c.text} for c in self.chunks]
        self.M = rag.build_index(self.chunks)
        self.bm = kb.BM25(self.rows)
        self.n = 0

    def search(self, q, k=3):
        b = list(np.argsort(-np.asarray(hy.bm25_scores(self.bm, q)), kind="stable"))
        v = list(np.argsort(-(self.M @ rag.embed([f"Instruct: {kb.QUERY_TASK}\nQuery:{q}"])[0])))
        return hy.rrf([b[:20], v[:20]])[:k]

    def fmt(self, idx):
        out = []
        for j in idx:
            self.n += 1
            r = self.rows[j]
            out.append(f"[S{self.n}]（{r['doc_key']} › {r['section'].split(' › ')[-1]}）{r['text']}")
        return "\n\n".join(out)


def ask(system, user, max_tokens=300):
    r = client.messages.create(model=MODEL, max_tokens=max_tokens, system=system,
                               messages=[{"role": "user", "content": user}], extra_body=NOTHINK)
    return "".join(b.text for b in r.content if b.type == "text").strip()


def sections_of(kbase, idx):
    return [kbase.rows[j]["section"].split(" › ")[-1] for j in idx]


def run_a(kbase, q):
    kbase.n = 0
    idx = kbase.search(q, k=4)
    ans = ask(SYSTEM, f"资料：\n{kbase.fmt(idx)}\n\n问题：{q}")
    return {"searches": [q], "got": sections_of(kbase, idx), "answer": ans, "calls": 1}


def run_b(kbase, q):
    kbase.n = 0
    plan = ask("把用户的问题拆成要去知识库查的 2 到 3 件事，每件事一行检索语句，只输出这几行。知识库里有 order-svc 运维手册和故障单。", q, 120)
    queries = [x.strip(" -•0123456789.、") for x in plan.splitlines() if x.strip()][:3]
    idx = []
    for s in queries:
        for j in kbase.search(s, k=2):
            if j not in idx:
                idx.append(j)
    ans = ask(SYSTEM, f"资料：\n{kbase.fmt(idx)}\n\n问题：{q}")
    return {"searches": queries, "got": sections_of(kbase, idx), "answer": ans, "calls": 2}


def run_c(kbase, q, force_first=False, think=False):
    kbase.n = 0
    messages = [{"role": "user", "content": q}]
    searches, got, calls = [], [], 0
    for turn in range(MAX_CALLS + 1):
        tools = [TOOL] if len(searches) < MAX_CALLS else []        # 查满 4 次就把工具收走，只能回答
        choice = {"type": "any"} if (force_first and not searches and tools) else anthropic.NOT_GIVEN
        r = client.messages.create(model=MODEL, max_tokens=3000 if think else 400, system=SYSTEM + "需要资料时调用 search_kb。",
                                   tools=tools or anthropic.NOT_GIVEN, tool_choice=choice, messages=messages,
                                   extra_body={} if think else NOTHINK)
        calls += 1
        uses = [b for b in r.content if b.type == "tool_use"]
        if not uses:
            ans = "".join(b.text for b in r.content if b.type == "text").strip()
            return {"searches": searches, "got": got, "answer": ans, "calls": calls, "stop": r.stop_reason}
        messages.append({"role": "assistant", "content": r.content})
        results = []
        for u in uses:
            s = u.input.get("query", "")
            searches.append(s)
            idx = kbase.search(s, k=3)
            got += sections_of(kbase, idx)
            results.append({"type": "tool_result", "tool_use_id": u.id, "content": kbase.fmt(idx)})
        messages.append({"role": "user", "content": results})
    return {"searches": searches, "got": got, "answer": "（到上限还没回答）", "calls": calls, "stop": "limit"}


# 对照：人按问题类型写好的检索计划（代码决定查什么），看“查对了”时 1.7B 能不能答对
HAND = {"A1": ["INC-20261109-02 时间线 发布 告警", "发布后多久内必须回滚，超过时间怎么办", "关闭本地缓存的降级开关"],
        "A2": ["OrderDetailCache 根因", "本地缓存必须设置 maximumSize 和过期时间的规定"],
        "A3": ["INC-20261108-01 根因", "INC-20261109-02 根因"],
        "A4": ["关闭本地缓存的降级开关 生效"]}


def run_f(kbase, q):
    kbase.n = 0
    qid = next(x[0] for x in QUESTIONS if x[1] == q)
    idx = []
    for s in HAND[qid]:
        for j in kbase.search(s, k=2):
            if j not in idx:
                idx.append(j)
    ans = ask(SYSTEM, f"资料：\n{kbase.fmt(idx)}\n\n问题：{q}")
    return {"searches": HAND[qid], "got": sections_of(kbase, idx), "answer": ans, "calls": 1}


METHODS = {"F": ("F 人写检索计划", run_f), "A": ("A 只查一次", run_a), "B": ("B 先列计划", run_b), "C": ("C agent 循环", run_c),
           "D": ("D 第一轮必须查", lambda k, q: run_c(k, q, force_first=True)),
           "E": ("E 必须查 + 思考", lambda k, q: run_c(k, q, force_first=True, think=True))}


def main():
    # 用法：main.py [方法字母...] [--only A1,A2]；默认 A B C D 全部 4 题
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    only = next((a.split("=", 1)[1].split(",") for a in sys.argv[1:] if a.startswith("--only=")), None)
    kbase = KB()
    path = HERE / "runs.json"
    out = json.loads(path.read_text()) if path.exists() and args else []
    for qid, q, ref, need in QUESTIONS:
        if only and qid not in only:
            continue
        for name, fn in [METHODS[m] for m in (args or "ABCD")]:
            t0 = time.perf_counter()
            r = fn(kbase, q)
            r.update({"qid": qid, "q": q, "ref": ref, "need": need, "method": name, "sec": round(time.perf_counter() - t0, 1)})
            r["covered"] = [n for n in need if any(n in g for g in r["got"])]
            out = [x for x in out if not (x["qid"] == qid and x["method"] == name)] + [r]
            print(f"\n{qid} {name}（{r['sec']}s，模型调用 {r['calls']} 次）  需要 {need}，拿到了 {r['covered']}")
            print(f"   查了：{r['searches']}")
            print(f"   答：{r['answer'][:220]}")
            path.write_text(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
