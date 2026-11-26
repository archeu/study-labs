"""站 09 的评测运行器：值班助手 × 50 条用例，逐条留下对话、每次调用的耗时和 token、判分。

被测系统 = pano-04 的 5 个工具 + 站 04 改过的工具说明（v2）+ prompts/ 下某一版系统提示词。
站 09 后面几页（打分、tracing、轨迹、版本、CI、换模型）都 import 这个文件：
    sys.path.insert(0, str(Path(__file__).parent.parent / "eval-dataset")); import harness
"""

import copy
import hashlib
import importlib
import json
import os
import re
import sys
import time
from pathlib import Path

import anthropic

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "pano-04-triage-agent"))
sys.path.insert(0, str(HERE))
import oncall  # noqa: E402
from cases50 import CASES  # noqa: E402

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen3-1.7b")
LOCAL = bool(BASE_URL)
client = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"), timeout=900)
MAX_STEPS = 8
EMPTY = ("没有匹配", "这段时间没有")
FIELD_TOOLS = {"search_logs", "parse_gc_log", "thread_dump"}   # 现场证据；search_notes 是知识库，不算
PROMPTS = HERE / "prompts"
RUNS = HERE / "runs"


# ---------- 被测系统 ----------
def load_prompt(version: str) -> tuple[str, str]:
    text = (PROMPTS / f"oncall-system{version}.md").read_text().strip()
    return text, hashlib.sha256(text.encode()).hexdigest()[:8]


def tools_v2() -> list:
    """站 04 改过的工具说明：时间、关键字、空结果怎么办。"""
    tools = copy.deepcopy(oncall.TOOLS)
    for t in tools:
        props = t["input_schema"]["properties"]
        if "since" in props:
            props["since"]["description"] = ("从几点开始查，HH:MM。告警通常比问题晚 5～15 分钟，填告警时间往前 15 分钟："
                                             "告警 20:16 就填 20:00。不确定就不填，默认查告警前 30 分钟。")
        if t["name"] == "search_logs":
            props["keyword"]["description"] = ("正则。日志里的写法是：耗时、超时、WARN、ERROR、Exception、上线；"
                                               "不要用告警里的 P99、慢请求。不确定就用 WARN|ERROR|耗时|超时")
    return tools


def alert_since(question: str) -> str:
    m = re.search(r"(\d\d):(\d\d) 告警", question)
    if not m:
        return "00:00"
    mins = max(0, int(m.group(1)) * 60 + int(m.group(2)) - 30)
    return f"{mins // 60:02d}:{mins % 60:02d}"


def setup(case):
    importlib.reload(oncall)                       # 每条用例从干净的现场开始
    fx = case["fixtures"]
    for k in ("APP_LOG", "GC_LOG", "THREAD_DUMP"):
        if k in fx:
            setattr(oncall, k, fx[k])
    if "APP_LOG_APPEND" in fx:
        oncall.APP_LOG += "\n" + fx["APP_LOG_APPEND"]
    if case.get("tool_error"):
        def broken(**_):
            raise FileNotFoundError("文件不存在（可能刚被轮转）或采集超时")
        oncall.IMPL[case["tool_error"]] = broken


def run_tool(b, case) -> dict:
    if b.name not in oncall.IMPL:
        return {"type": "tool_result", "tool_use_id": b.id, "is_error": True, "content": f"没有叫 {b.name} 的工具"}
    if b.name in oncall.NEEDS_APPROVAL:
        return {"type": "tool_result", "tool_use_id": b.id, "is_error": True,
                "content": "没有值班人员确认（超时），按拒绝处理。请用已有证据给结论。"}
    args = dict(b.input or {})
    if b.name in ("search_logs", "parse_gc_log"):
        args.setdefault("since", alert_since(case["question"]))
    try:
        out = oncall.IMPL[b.name](**args)[:1500]
        if out.startswith(EMPTY):
            out += f"（查的是 {args.get('since', '全部')} 以后、关键字 {args.get('keyword', '-')}。可以把 since 往前放，或用 WARN|ERROR|耗时|超时）"
        return {"type": "tool_result", "tool_use_id": b.id, "content": out}
    except Exception as e:  # noqa: BLE001
        return {"type": "tool_result", "tool_use_id": b.id, "is_error": True, "content": f"{type(e).__name__}: {e}"}


def run_agent(case, system: str, thinking=False):
    """跑一条用例。返回对话和 spans：每次模型调用、每次工具调用一条，带开始时间、耗时、token。"""
    tools = tools_v2()
    messages, spans, t_start = [{"role": "user", "content": case["question"]}], [], time.perf_counter()
    extra = {"chat_template_kwargs": {"enable_thinking": thinking}} if LOCAL else {}
    for step in range(1, MAX_STEPS + 1):
        t0 = time.perf_counter()
        r = client.messages.create(model=MODEL, max_tokens=2048, system=system, tools=tools,
                                   messages=messages, extra_body=extra)
        u = r.usage
        calls = [b for b in r.content if b.type == "tool_use"]
        spans.append({"kind": "chat", "step": step, "start_ms": round((t0 - t_start) * 1000), "ms": round((time.perf_counter() - t0) * 1000),
                      "input_tokens": u.input_tokens, "output_tokens": u.output_tokens,
                      "cache_read_tokens": getattr(u, "cache_read_input_tokens", 0) or 0,
                      "stop_reason": r.stop_reason, "tool_calls": [c.name for c in calls]})
        messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in r.content]})
        if r.stop_reason != "tool_use":
            break
        results = []
        for c in calls:
            t1 = time.perf_counter()
            res = run_tool(c, case)
            results.append(res)
            spans.append({"kind": "tool", "step": step, "name": c.name, "args": dict(c.input or {}),
                          "start_ms": round((t1 - t_start) * 1000), "ms": round((time.perf_counter() - t1) * 1000, 2),
                          "error": bool(res.get("is_error")), "empty": str(res["content"]).startswith(EMPTY),
                          "result_chars": len(str(res["content"]))})
        messages.append({"role": "user", "content": results})
    return messages, spans


# ---------- 判分：只用代码能判的（站 04 的规则 + 不许调用的工具） ----------
def numbers(text):
    return set(re.findall(r"\d[\d,.]*\s?(?:ms|s|%|MB|M|GB|行|次|秒|个|条)", text))


def final_answer(messages) -> str:
    last = [m for m in messages if m["role"] == "assistant"][-1]
    return "".join(b.get("text", "") for b in last["content"] if b["type"] == "text").strip()


def cause_part(answer: str) -> str:
    i = answer.find("原因")                     # “原因”那一段：到“证据 / 下一步 / 建议”为止
    if i < 0:
        return answer[:200]
    ends = [j for j in (answer.find(w, i + 2) for w in ("证据", "下一步", "建议")) if j > 0]
    return answer[i:min(ends + [i + 300])]


def grade(case, messages):
    assistants = [m for m in messages if m["role"] == "assistant"]
    answer = final_answer(messages)
    calls = [b for m in assistants for b in m["content"] if b["type"] == "tool_use"]
    results = [b for m in messages if m["role"] == "user" and isinstance(m["content"], list) for b in m["content"]]
    ok = [r for r in results if not r.get("is_error")]
    evidence = (case["question"] + "\n" + "\n".join(str(r["content"]) for r in ok)).replace(" ", "")
    fabricated = sorted(n for n in numbers(answer) if n.replace(" ", "") not in evidence)
    hit = any(k.lower() in answer.lower() for k in case["expect_any"])
    blame = [w for w in case["must_not"] if w.lower() in cause_part(answer).lower()]
    field_ids = {c["id"] for c in calls if c["name"] in FIELD_TOOLS}
    saw_field = any(r["tool_use_id"] in field_ids and not str(r["content"]).startswith(EMPTY) for r in ok)
    forbidden = [c["name"] for c in calls if c["name"] in case.get("must_not_call", [])]
    g = {"空回答": not answer, "看过现场": saw_field, "说中": hit, "怪错": blame, "编造数字": fabricated,
         "违规调用": forbidden, "轮数": len(assistants), "工具调用": len(calls), "到上限": len(assistants) >= MAX_STEPS}
    g["通过"] = bool(answer) and saw_field and hit and not blame and not fabricated and not forbidden
    return g, answer


def why(g) -> str:
    return ("空回答" if g["空回答"] else "没看现场" if not g["看过现场"] else "没说中" if not g["说中"]
            else f"怪错{g['怪错']}" if g["怪错"] else f"编造{g['编造数字'][:2]}" if g["编造数字"]
            else f"违规{g['违规调用']}" if g["违规调用"] else "")


# ---------- 汇总 ----------
def summarize(rows: list, meta: dict) -> dict:
    n = len(rows)
    s = {**meta, "共": n, "通过": sum(r["通过"] for r in rows), "看过现场": sum(r["看过现场"] for r in rows),
         "说中": sum(r["说中"] for r in rows), "怪错": sum(bool(r["怪错"]) for r in rows),
         "编造数字": sum(bool(r["编造数字"]) for r in rows), "违规调用": sum(bool(r["违规调用"]) for r in rows),
         "平均轮数": round(sum(r["轮数"] for r in rows) / max(1, n), 1),
         "平均秒": round(sum(r["秒"] for r in rows) / max(1, n), 1),
         "平均输出token": round(sum(r["输出token"] for r in rows) / max(1, n))}
    by_cat = {}
    for r in rows:
        c = by_cat.setdefault(r["cat"], [0, 0])
        c[0] += r["通过"]
        c[1] += 1
    s["按类型"] = {k: f"{v[0]}/{v[1]}" for k, v in by_cat.items()}
    return s


def run_suite(version: str, name: str, only=None, thinking=False):
    system, sha = load_prompt(version)
    out = RUNS / name
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for case in CASES:
        if only and not any(case["id"].startswith(o) for o in only):
            continue
        setup(case)
        t0 = time.perf_counter()
        messages, spans = run_agent(case, system, thinking)
        dt = time.perf_counter() - t0
        g, answer = grade(case, messages)
        chats = [s for s in spans if s["kind"] == "chat"]
        g.update({"id": case["id"], "cat": case["cat"], "src": case["src"], "秒": round(dt, 1),
                  "输入token": sum(s["input_tokens"] for s in chats), "输出token": sum(s["output_tokens"] for s in chats),
                  "工具序列": [s["name"] for s in spans if s["kind"] == "tool"]})
        rows.append(g)
        (out / f"{case['id']}.json").write_text(json.dumps(
            {"case": case["id"], "prompt": version, "prompt_sha": sha, "model": MODEL, "thinking": thinking,
             "messages": messages, "spans": spans, "grade": g}, ensure_ascii=False, indent=1))
        print(f"{'✓' if g['通过'] else '✕'} {case['id']:22} {g['轮数']} 轮 {dt:5.1f}s {why(g):14} {'→'.join(g['工具序列'])[:60]}", flush=True)
    meta = {"提示词": version, "sha": sha, "模型": MODEL, "思考": thinking, "日期": time.strftime("%Y-%m-%d %H:%M")}
    summary = summarize(rows, meta)
    (out / "scores.json").write_text(json.dumps({"summary": summary, "cases": rows}, ensure_ascii=False, indent=1))
    return summary


def regrade(name: str) -> dict:
    """判据改了时用：按存下的对话重新判分，耗时、token 照旧。"""
    out = RUNS / name
    old = json.loads((out / "scores.json").read_text())
    keep = {r["id"]: r for r in old["cases"]}
    rows = []
    for case in CASES:
        f = out / f"{case['id']}.json"
        if not f.exists():
            continue
        rec = json.loads(f.read_text())
        g, _ = grade(case, rec["messages"])
        g.update({k: keep[case["id"]][k] for k in ("id", "cat", "src", "秒", "输入token", "输出token", "工具序列")})
        rows.append(g)
        rec["grade"] = g
        f.write_text(json.dumps(rec, ensure_ascii=False, indent=1))
    meta = {k: v for k, v in old["summary"].items() if k in ("提示词", "sha", "模型", "思考", "日期")}
    summary = summarize(rows, meta)
    (out / "scores.json").write_text(json.dumps({"summary": summary, "cases": rows}, ensure_ascii=False, indent=1))
    return summary


def load_scores(name: str) -> dict:
    p = Path(name) if Path(name).exists() else RUNS / name
    return json.loads((p / "scores.json").read_text())
