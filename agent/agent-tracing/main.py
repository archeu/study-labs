"""给值班助手接上 OpenTelemetry：一次排障一个 trace，每次模型调用、每次工具调用各一个 span。

span 的名字和属性照 OpenTelemetry GenAI 语义约定（open-telemetry/semantic-conventions-genai，Development 状态）：
    invoke_agent oncall        gen_ai.operation.name=invoke_agent，gen_ai.prompt.name / gen_ai.prompt.version
      chat qwen3-1.7b          gen_ai.usage.input_tokens（含缓存命中）/ output_tokens / cache_read.input_tokens
      execute_tool search_logs gen_ai.tool.name / gen_ai.tool.call.id / arguments / result（后两个是 opt-in，含原文）

    uv run python agent-tracing/main.py live 02 --prompt @3          # 真跑一条：边跑边记 span，写进 traces/live.jsonl
    uv run python agent-tracing/main.py convert pr57-@4-r1           # 把 eval-dataset 存下的调用记录转成 OTel span（不重跑模型）
    uv run python agent-tracing/main.py show traces/live.jsonl       # 终端里画瀑布图（--case 02 只看一条）
    uv run python agent-tracing/main.py where base-@3-r1             # 50 条的时间和 token 花在哪
"""

import json
import os
import sys
import time
from pathlib import Path

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor, SpanExporter, SpanExportResult
from opentelemetry.trace import SpanKind, Status, StatusCode

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "eval-dataset"))
import harness  # noqa: E402

PROVIDER = "anthropic" if not harness.LOCAL else "llama.cpp"
TRACES = HERE / "traces"


class JsonlExporter(SpanExporter):
    """每个 span 一行 JSON。线上会换成 OTLP 导出器，发给 Jaeger、Langfuse 这类后台。"""

    def __init__(self, path: Path):
        path.parent.mkdir(exist_ok=True)
        self.f = open(path, "a", encoding="utf-8")

    def export(self, spans):
        for s in spans:
            self.f.write(s.to_json(indent=None) + "\n")
        self.f.flush()
        return SpanExportResult.SUCCESS

    def shutdown(self):
        self.f.close()


def make_tracer(path: Path):
    tp = TracerProvider(resource=Resource.create({"service.name": "oncall-assistant", "service.version": "station09"}))
    tp.add_span_processor(SimpleSpanProcessor(JsonlExporter(path)))
    return tp.get_tracer("oncall.tracing"), tp


def chat_attrs(model, u, stop_reason):
    cache = getattr(u, "cache_read_input_tokens", 0) or 0
    return {"gen_ai.operation.name": "chat", "gen_ai.provider.name": PROVIDER, "gen_ai.request.model": model,
            # 约定：input_tokens 要包含缓存命中的部分。Anthropic 和 llama.cpp 的 usage.input_tokens 都不含，要加回去
            "gen_ai.usage.input_tokens": u.input_tokens + cache, "gen_ai.usage.cache_read.input_tokens": cache,
            "gen_ai.usage.output_tokens": u.output_tokens, "gen_ai.response.finish_reasons": [stop_reason or ""]}


# ---------- 1. 真跑：在 agent 循环里埋点 ----------
def run_traced(tracer, case, version, thinking=False):
    system, sha = harness.load_prompt(version)
    tools = harness.tools_v2()
    harness.setup(case)
    extra = {"chat_template_kwargs": {"enable_thinking": thinking}} if harness.LOCAL else {}
    messages = [{"role": "user", "content": case["question"]}]
    with tracer.start_as_current_span("invoke_agent oncall", kind=SpanKind.INTERNAL, attributes={
            "gen_ai.operation.name": "invoke_agent", "gen_ai.agent.name": "oncall", "gen_ai.request.model": harness.MODEL,
            "gen_ai.prompt.name": "oncall-system", "gen_ai.prompt.version": version, "oncall.prompt.sha": sha,
            "eval.case.id": case["id"]}) as root:
        for step in range(1, harness.MAX_STEPS + 1):
            with tracer.start_as_current_span(f"chat {harness.MODEL}", kind=SpanKind.CLIENT) as sp:
                r = harness.client.messages.create(model=harness.MODEL, max_tokens=2048, system=system, tools=tools,
                                                   messages=messages, extra_body=extra)
                sp.set_attributes(chat_attrs(harness.MODEL, r.usage, r.stop_reason))
                sp.set_attribute("oncall.step", step)
            calls = [b for b in r.content if b.type == "tool_use"]
            messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in r.content]})
            if r.stop_reason != "tool_use":
                break
            results = []
            for c in calls:
                with tracer.start_as_current_span(f"execute_tool {c.name}", kind=SpanKind.INTERNAL, attributes={
                        "gen_ai.operation.name": "execute_tool", "gen_ai.tool.name": c.name, "gen_ai.tool.call.id": c.id,
                        "gen_ai.tool.call.arguments": json.dumps(c.input or {}, ensure_ascii=False)}) as ts:
                    res = harness.run_tool(c, case)
                    ts.set_attribute("gen_ai.tool.call.result", str(res["content"])[:300])
                    if res.get("is_error"):
                        ts.set_status(Status(StatusCode.ERROR, str(res["content"])[:120]))
                        ts.set_attribute("error.type", "tool_error")
                results.append(res)
            messages.append({"role": "user", "content": results})
        g, answer = harness.grade(case, messages)
        root.set_attribute("eval.passed", g["通过"])
        root.set_attribute("oncall.answer.head", answer[:120])
    return g, answer


# ---------- 2. 转换：把评测时存下的调用记录变成 OTel span ----------
def convert(tracer, rec, t0_ns):
    root_end = t0_ns + max(s["start_ms"] + s["ms"] for s in rec["spans"]) * 1_000_000
    root = tracer.start_span("invoke_agent oncall", kind=SpanKind.INTERNAL, start_time=t0_ns, attributes={
        "gen_ai.operation.name": "invoke_agent", "gen_ai.agent.name": "oncall", "gen_ai.request.model": rec["model"],
        "gen_ai.prompt.name": "oncall-system", "gen_ai.prompt.version": rec["prompt"], "oncall.prompt.sha": rec["prompt_sha"],
        "eval.case.id": rec["case"], "eval.passed": rec["grade"]["通过"]})
    ctx = trace.set_span_in_context(root)
    for s in rec["spans"]:
        start = int(t0_ns + s["start_ms"] * 1_000_000)
        end = int(start + s["ms"] * 1_000_000)
        if s["kind"] == "chat":
            attrs = {"gen_ai.operation.name": "chat", "gen_ai.provider.name": PROVIDER, "gen_ai.request.model": rec["model"],
                     "gen_ai.usage.input_tokens": s["input_tokens"] + s["cache_read_tokens"],
                     "gen_ai.usage.cache_read.input_tokens": s["cache_read_tokens"], "gen_ai.usage.output_tokens": s["output_tokens"],
                     "gen_ai.response.finish_reasons": [s["stop_reason"] or ""], "oncall.step": s["step"],
                     "oncall.tool_calls": ",".join(s["tool_calls"])}
            sp = tracer.start_span(f"chat {rec['model']}", context=ctx, kind=SpanKind.CLIENT, start_time=start, attributes=attrs)
        else:
            attrs = {"gen_ai.operation.name": "execute_tool", "gen_ai.tool.name": s["name"],
                     "gen_ai.tool.call.arguments": json.dumps(s["args"], ensure_ascii=False), "oncall.step": s["step"],
                     "oncall.result_chars": s["result_chars"], "oncall.result_empty": s["empty"]}
            sp = tracer.start_span(f"execute_tool {s['name']}", context=ctx, kind=SpanKind.INTERNAL, start_time=start, attributes=attrs)
            if s["error"]:
                sp.set_status(Status(StatusCode.ERROR))
                sp.set_attribute("error.type", "tool_error")
        sp.end(end_time=end)
    root.end(end_time=int(root_end))


# ---------- 3. 看：瀑布图和汇总 ----------
def load_spans(path: Path):
    spans = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    by_trace = {}
    for s in spans:
        by_trace.setdefault(s["context"]["trace_id"], []).append(s)
    return by_trace


def ts(s):  # SDK 的 to_json 里时间是 ISO 字符串
    from datetime import datetime
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def show(path: Path, case=None, width=48):
    for tid, spans in load_spans(path).items():
        root = next(s for s in spans if s["parent_id"] is None)
        if case and not root["attributes"].get("eval.case.id", "").startswith(case):
            continue
        t0, t1 = ts(root["start_time"]), ts(root["end_time"])
        a = root["attributes"]
        print(f"\ntrace {tid[-8:]}  用例 {a.get('eval.case.id')}  提示词 {a.get('gen_ai.prompt.version')}  "
              f"{'通过' if a.get('eval.passed') else '没过'}  共 {t1 - t0:.1f}s")
        for s in sorted(spans, key=lambda x: (ts(x["start_time"]), x["parent_id"] is not None)):
            b, e = ts(s["start_time"]) - t0, ts(s["end_time"]) - t0
            lo, hi = int(b / (t1 - t0) * width), max(int(b / (t1 - t0) * width) + 1, int(e / (t1 - t0) * width))
            sa = s["attributes"]
            note = ""
            if sa.get("gen_ai.operation.name") == "chat":
                note = f"入 {sa['gen_ai.usage.input_tokens']}（缓存 {sa.get('gen_ai.usage.cache_read.input_tokens', 0)}）出 {sa['gen_ai.usage.output_tokens']}"
                if sa.get("oncall.tool_calls"):
                    note += f" → {sa['oncall.tool_calls']}"
            elif sa.get("gen_ai.operation.name") == "execute_tool":
                note = sa.get("gen_ai.tool.call.arguments", "")[:40] + (" 空" if sa.get("oncall.result_empty") else "") + \
                    (" 出错" if s["status"]["status_code"] == "ERROR" else "")
            indent = "" if s["parent_id"] is None else "  "
            print(f"{indent + s['name']:34} {' ' * lo}{'█' * (hi - lo):{width - lo}} {e - b:6.2f}s {note}")


def where(run):
    """不经过 OTel，直接从存下的调用记录里汇总：时间和 token 花在哪。"""
    recs = [json.loads(f.read_text()) for f in sorted((harness.RUNS / run).glob("[0-9][0-9]-*.json"))]
    chat_ms = sum(s["ms"] for r in recs for s in r["spans"] if s["kind"] == "chat")
    tool_ms = sum(s["ms"] for r in recs for s in r["spans"] if s["kind"] == "tool")
    per_case = sorted(max(s["start_ms"] + s["ms"] for s in r["spans"]) / 1000 for r in recs)
    chats = [s for r in recs for s in r["spans"] if s["kind"] == "chat"]
    first = [s for s in chats if s["step"] == 1]
    last = [s for r in recs for s in [max((x for x in r["spans"] if x["kind"] == "chat"), key=lambda x: x["step"])]]
    pct = lambda xs, p: xs[min(len(xs) - 1, int(round(p * (len(xs) - 1))))]  # noqa: E731
    print(f"{run}：{len(recs)} 条，模型调用 {len(chats)} 次")
    print(f"时间：模型 {chat_ms / 1000:.0f}s，工具 {tool_ms / 1000:.2f}s（工具占 {tool_ms / (chat_ms + tool_ms) * 100:.3f}%）")
    print(f"每条耗时：P50 {pct(per_case, .5):.0f}s  P95 {pct(per_case, .95):.0f}s  最长 {per_case[-1]:.0f}s")
    print(f"每次模型调用：平均 {chat_ms / len(chats) / 1000:.1f}s；输入 token 平均 {sum(s['input_tokens'] + s['cache_read_tokens'] for s in chats) / len(chats):.0f}"
          f"（其中缓存命中 {sum(s['cache_read_tokens'] for s in chats) / len(chats):.0f}），输出平均 {sum(s['output_tokens'] for s in chats) / len(chats):.0f}")
    print(f"第 1 轮平均 {sum(s['ms'] for s in first) / len(first) / 1000:.1f}s、输出 {sum(s['output_tokens'] for s in first) / len(first):.0f} token；"
          f"最后一轮（写结论）平均 {sum(s['ms'] for s in last) / len(last) / 1000:.1f}s、输出 {sum(s['output_tokens'] for s in last) / len(last):.0f} token")
    return {"n": len(recs), "chat_s": chat_ms / 1000, "tool_s": tool_ms / 1000, "p50": pct(per_case, .5), "p95": pct(per_case, .95)}


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "live":
        version = sys.argv[sys.argv.index("--prompt") + 1] if "--prompt" in sys.argv else "@3"
        tracer, tp = make_tracer(TRACES / "live.jsonl")
        case = next(c for c in harness.CASES if c["id"].startswith(sys.argv[2]))
        t0 = time.perf_counter()
        g, answer = run_traced(tracer, case, version)
        tp.shutdown()
        print(f"{case['id']} {version} {'通过' if g['通过'] else '没过：' + harness.why(g)}  {time.perf_counter() - t0:.0f}s")
        show(TRACES / "live.jsonl", case=sys.argv[2])
    elif cmd == "convert":
        run = sys.argv[2]
        out = TRACES / f"{run}.jsonl"
        out.unlink(missing_ok=True)
        tracer, tp = make_tracer(out)
        base = time.time_ns()
        for i, f in enumerate(sorted((harness.RUNS / run).glob("[0-9][0-9]-*.json"))):
            convert(tracer, json.loads(f.read_text()), base + i * 600 * 1_000_000_000)
        tp.shutdown()
        print(f"写到 {out.relative_to(HERE.parent)}：{sum(1 for _ in out.open())} 个 span")
    elif cmd == "show":
        show(Path(sys.argv[2]), case=sys.argv[sys.argv.index("--case") + 1] if "--case" in sys.argv else None)
    elif cmd == "where":
        where(sys.argv[2])
    else:
        print(__doc__)
