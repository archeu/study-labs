"""先写 10 条评测用例，后面每站都跑：用 cases.py 的 10 个现场跑值班助手 v1，按 SOP 的指标判分。

    python main.py                  # 10 条全跑（本机 Qwen3-1.7B，默认关思考，约十几分钟）
    python main.py --only 02 05     # 只跑某几条
    python main.py --thinking       # 开思考（本机慢很多）
    python main.py --regrade 2026-11-24   # 不重跑，按存下的对话重新判分
    python main.py --v2             # 只改工具说明（时间、关键字、空结果提示），同样 10 条再跑一遍
    ANTHROPIC_BASE_URL= ANTHROPIC_API_KEY=sk-ant-... MODEL=claude-sonnet-5-5 python main.py   # 连 Claude

每条的完整对话写进 runs/<日期>/<用例>.json，汇总写进 runs/<日期>/scores.json。heap_dump 一律按“没人审批”拒绝。
"""

import importlib
import json
import os
import re
import sys
import time
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402
from cases import CASES  # noqa: E402

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen3-1.7b")
client = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"), timeout=300)
THINKING = "--thinking" in sys.argv
LOCAL = bool(BASE_URL)
MAX_STEPS = 8
EMPTY = ("没有匹配", "这段时间没有")
FIELD_TOOLS = {"search_logs", "parse_gc_log", "thread_dump"}   # 现场证据；search_notes 是知识库，不算


# ---------- 被测的系统：值班助手 v1（和全景页同一套工具、提示词、循环） ----------
def setup(case):
    importlib.reload(oncall)                                 # 每条用例从干净的现场开始
    fx = case["fixtures"]
    for k in ("APP_LOG", "GC_LOG", "THREAD_DUMP"):
        if k in fx:
            setattr(oncall, k, fx[k])
    if "APP_LOG_APPEND" in fx:
        oncall.APP_LOG += "\n" + fx["APP_LOG_APPEND"]
    if V2:
        apply_v2()
    if case.get("tool_error"):
        def broken(**_):
            raise FileNotFoundError("gc.log 不存在（可能刚被轮转）")
        oncall.IMPL[case["tool_error"]] = broken


V2 = "--v2" in sys.argv          # 只改工具说明和空结果提示（工具怎么设计那页的改法 + 关键字用日志原词）


def apply_v2():
    for t in oncall.TOOLS:
        props = t["input_schema"]["properties"]
        if "since" in props:
            props["since"]["description"] = ("从几点开始查，HH:MM。告警通常比问题晚 5～15 分钟，填告警时间往前 15 分钟："
                                             "告警 20:16 就填 20:00。不确定就不填，默认查告警前 30 分钟。")
        if t["name"] == "search_logs":
            props["keyword"]["description"] = ("正则。日志里的写法是：耗时、超时、WARN、ERROR、Exception、上线；"
                                               "不要用告警里的 P99、慢请求。不确定就用 WARN|ERROR|耗时|超时")


def run_tool(b) -> dict:
    if b.name not in oncall.IMPL:
        return {"type": "tool_result", "tool_use_id": b.id, "is_error": True, "content": f"没有叫 {b.name} 的工具"}
    if b.name in oncall.NEEDS_APPROVAL:
        return {"type": "tool_result", "tool_use_id": b.id, "is_error": True,
                "content": "没有值班人员确认（超时），按拒绝处理。请用已有证据给结论。"}
    args = dict(b.input or {})
    if V2 and b.name in ("search_logs", "parse_gc_log"):
        args.setdefault("since", "19:46")
    try:
        out = oncall.IMPL[b.name](**args)[:1500]
        if V2 and out.startswith(EMPTY):
            out += f"（查的是 {args.get('since', '全部')} 以后、关键字 {args.get('keyword', '-')}。可以把 since 往前放，或用 WARN|ERROR|耗时|超时）"
        return {"type": "tool_result", "tool_use_id": b.id, "content": out}
    except Exception as e:  # noqa: BLE001
        return {"type": "tool_result", "tool_use_id": b.id, "is_error": True, "content": f"{type(e).__name__}: {e}"}


def run_agent(question):
    messages, usage = [{"role": "user", "content": question}], 0
    extra = {"chat_template_kwargs": {"enable_thinking": THINKING}} if LOCAL else {}
    for _ in range(MAX_STEPS):
        r = client.messages.create(model=MODEL, max_tokens=2048, system=oncall.SYSTEM, tools=oncall.TOOLS,
                                   messages=messages, extra_body=extra)
        usage += r.usage.input_tokens + r.usage.output_tokens
        messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in r.content]})
        if r.stop_reason != "tool_use":
            break
        messages.append({"role": "user", "content": [run_tool(b) for b in r.content if b.type == "tool_use"]})
    return messages, usage


# ---------- 判分：只用代码能判的 ----------
def numbers(text):
    return set(re.findall(r"\d[\d,.]*\s?(?:ms|s|%|MB|M|GB|行|次|秒)", text))


def grade(case, messages):
    assistants = [m for m in messages if m["role"] == "assistant"]
    answer = "".join(b.get("text", "") for b in assistants[-1]["content"] if b["type"] == "text").strip()
    calls = [b for m in assistants for b in m["content"] if b["type"] == "tool_use"]
    results = [b for m in messages if m["role"] == "user" and isinstance(m["content"], list) for b in m["content"]]
    ok = [r for r in results if not r.get("is_error")]
    question = messages[0]["content"] if isinstance(messages[0]["content"], str) else ""
    evidence = (question + "\n" + "\n".join(str(r["content"]) for r in ok)).replace(" ", "")   # 告警里的数字也算证据
    i = answer.find("原因")                                   # “原因”那一段：到“证据 / 下一步 / 建议”为止
    if i < 0:
        cause = answer[:200]
    else:
        ends = [j for j in (answer.find(w, i + 2) for w in ("证据", "下一步", "建议")) if j > 0]
        cause = answer[i:min(ends + [i + 300])]
    nums = numbers(answer)
    fabricated = sorted(n for n in nums if n.replace(" ", "") not in evidence)
    hit = any(k.lower() in answer.lower() for k in case["expect_any"])
    blame = [w for w in case["must_not"] if w.lower() in cause.lower()]
    field_ids = {c["id"] for c in calls if c["name"] in FIELD_TOOLS}
    saw_field = any(r["tool_use_id"] in field_ids and not str(r["content"]).startswith(EMPTY) for r in ok)
    g = {"空回答": not answer, "看过现场": saw_field, "说中": hit, "怪错": blame, "编造数字": fabricated,
         "轮数": len(assistants), "工具调用": len(calls),
         "落空": sum(1 for r in ok if str(r["content"]).startswith(EMPTY)), "结果数": len(ok),
         "提议heap_dump": sum(1 for c in calls if c["name"] == "heap_dump"), "到上限": len(assistants) >= MAX_STEPS}
    g["通过"] = bool(answer) and saw_field and hit and not blame and not fabricated
    return g, answer


def regrade(day: str):
    """不重跑模型，按存下的对话重新判分（判分规则改了时用）。"""
    d = Path(__file__).parent / "runs" / day
    old = json.loads((d / "scores.json").read_text())
    meta = {r["id"]: r for r in old["cases"]}
    rows = []
    for case in CASES:
        f = d / f"{case['id']}.json"
        if not f.exists():
            continue
        g, answer = grade(case, json.loads(f.read_text()))
        g.update({"id": case["id"], "秒": meta[case["id"]]["秒"], "token": meta[case["id"]]["token"]})
        rows.append(g)
        why = "空回答" if g["空回答"] else "没看现场" if not g["看过现场"] else "没说中" if not g["说中"] else f"怪错 {g['怪错']}" if g["怪错"] else f"编造 {g['编造数字']}" if g["编造数字"] else ""
        print(f"{'✓' if g['通过'] else '✕'} {case['id']:20} {g['轮数']} 轮 {g['秒']:5.1f}s  {why}  | {answer[:60].replace(chr(10), ' ')}")
    return rows, old["summary"]["总秒"]


def summarize(rows, total_s):
    n = len(rows)
    return {"模型": MODEL, "思考": THINKING, "版本": "v2" if V2 else "v1", "通过": sum(r["通过"] for r in rows), "共": n,
            "看过现场": sum(r["看过现场"] for r in rows), "说中": sum(r["说中"] for r in rows), "怪错": sum(bool(r["怪错"]) for r in rows),
            "编造数字": sum(bool(r["编造数字"]) for r in rows), "空回答": sum(r["空回答"] for r in rows),
            "平均轮数": round(sum(r["轮数"] for r in rows) / max(1, n), 1),
            "落空率": round(sum(r["落空"] for r in rows) / max(1, sum(r["结果数"] for r in rows)), 2),
            "提议heap_dump": sum(r["提议heap_dump"] for r in rows), "到上限": sum(r["到上限"] for r in rows),
            "总秒": total_s, "总token": sum(r["token"] for r in rows)}


def main():
    if "--regrade" in sys.argv:
        day = sys.argv[sys.argv.index("--regrade") + 1]
        rows, total_s = regrade(day)
        summary = summarize(rows, total_s)
        (Path(__file__).parent / "runs" / day / "scores.json").write_text(json.dumps({"summary": summary, "cases": rows}, ensure_ascii=False, indent=1))
        print("\n" + json.dumps(summary, ensure_ascii=False))
        return
    only = sys.argv[sys.argv.index("--only") + 1:] if "--only" in sys.argv else None
    out = Path(__file__).parent / "runs" / (time.strftime("%Y-%m-%d") + ("-v2" if V2 else ""))
    out.mkdir(parents=True, exist_ok=True)
    rows, t_all = [], time.perf_counter()
    for case in CASES:
        if only and not any(case["id"].startswith(o) for o in only):
            continue
        setup(case)
        t0 = time.perf_counter()
        messages, usage = run_agent(case["question"])
        dt = time.perf_counter() - t0
        g, answer = grade(case, messages)
        g.update({"id": case["id"], "秒": round(dt, 1), "token": usage})
        rows.append(g)
        (out / f"{case['id']}.json").write_text(json.dumps(messages, ensure_ascii=False, indent=1))
        why = "空回答" if g["空回答"] else "没看现场" if not g["看过现场"] else "没说中" if not g["说中"] else f"怪错 {g['怪错']}" if g["怪错"] else f"编造 {g['编造数字']}" if g["编造数字"] else ""
        print(f"{'✓' if g['通过'] else '✕'} {case['id']:20} {g['轮数']} 轮 {dt:5.1f}s  {why}  | {answer[:60].replace(chr(10), ' ')}", flush=True)
    summary = summarize(rows, round(time.perf_counter() - t_all))
    (out / "scores.json").write_text(json.dumps({"summary": summary, "cases": rows}, ensure_ascii=False, indent=1))
    print("\n" + json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
