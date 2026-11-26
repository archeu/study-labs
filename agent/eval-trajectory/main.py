"""不只看结果，也看过程：从存下的调用记录里取出轨迹，逐条判“过程走得对不对”，再和结果交叉。

    uv run python eval-trajectory/main.py keys                       # 每条用例的关键证据在哪个工具里（从现场自动算）
    uv run python eval-trajectory/main.py judge base-@3-r1           # 每条的轨迹指标，结果和过程交叉成 2×2
    uv run python eval-trajectory/main.py compare base-@3-r1 pr57-@4-r1   # 两版的轨迹指标对照
    uv run python eval-trajectory/main.py show pr57-@4-r1 02         # 打印一条的轨迹

不重跑模型：用 eval-dataset 运行时存下的对话和 spans。
"""

import json
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "eval-dataset"))
import harness  # noqa: E402
import oncall  # noqa: E402

FIELD = harness.FIELD_TOOLS


def key_tools(case) -> set:
    """关键证据在哪：把现场布置好，每个现场工具用默认参数调一次，返回里出现说中词的就是关键工具。"""
    harness.setup(case)
    since = harness.alert_since(case["question"])
    outs = {}
    for name, call in (("search_logs", lambda: oncall.IMPL["search_logs"](keyword=".", since=since)),
                       ("parse_gc_log", lambda: oncall.IMPL["parse_gc_log"](since=since)),
                       ("thread_dump", lambda: oncall.IMPL["thread_dump"]())):
        try:
            outs[name] = call()
        except Exception:  # noqa: BLE001  工具被故意弄坏的用例
            outs[name] = ""
    return {n for n, o in outs.items() if any(w.lower() in o.lower() for w in case["expect_any"])}


def trajectory(rec, case, keys):
    """一条运行的轨迹指标。"""
    msgs = rec["messages"]
    calls, results = [], {}
    for i, m in enumerate(msgs):
        if m["role"] == "assistant":
            calls += [(sum(1 for x in msgs[:i + 1] if x["role"] == "assistant"), b) for b in m["content"] if b["type"] == "tool_use"]
        elif isinstance(m["content"], list):
            results.update({r["tool_use_id"]: r for r in m["content"] if r.get("type") == "tool_result"})
    seq = [b["name"] for _, b in calls]
    first_step_tools = [b["name"] for s, b in calls if s == 1]

    def got(b):
        r = results.get(b["id"], {})
        return "" if r.get("is_error") else str(r.get("content", ""))
    saw_key = any(b["name"] in keys and any(w.lower() in got(b).lower() for w in case["expect_any"]) for _, b in calls)
    field_steps = [s for s, b in calls if b["name"] in FIELD and got(b) and not got(b).startswith(harness.EMPTY)]
    kb_i = next((i for i, (_, b) in enumerate(calls) if b["name"] == "search_notes"), None)
    field_i = next((i for i, (_, b) in enumerate(calls) if b["name"] in FIELD and got(b) and not got(b).startswith(harness.EMPTY)), None)
    kb_first = kb_i is not None and (field_i is None or kb_i < field_i)   # 查空了就去翻知识库，也算
    sigs = [json.dumps([b["name"], b.get("input")], ensure_ascii=False, sort_keys=True) for _, b in calls]
    return {
        "第1轮": "+".join(first_step_tools) or "直接回答",
        "第1轮看现场": any(t in FIELD for t in first_step_tools),
        "第一次看到现场在第几轮": field_steps[0] if field_steps else None,
        "看到关键证据": saw_key,
        "知识库先于有效现场": kb_first,
        "重复调用": len(sigs) - len(set(sigs)),
        "提议堆转储": seq.count("heap_dump"),
        "轮数": rec["grade"]["轮数"],
        "通过": rec["grade"]["通过"],
        "序列": "→".join(seq),
    }


def load(run):
    cases = {c["id"]: c for c in harness.CASES}
    out = {}
    for f in sorted((harness.RUNS / run).glob("[0-9][0-9]-*.json")):
        rec = json.loads(f.read_text())
        c = cases[rec["case"]]
        out[rec["case"]] = trajectory(rec, c, KEYS[rec["case"]])
    return out


def quadrant(t):
    if t["通过"]:
        return "对，且看到了关键证据" if t["看到关键证据"] else "对，但没看到关键证据（碰巧）"
    return "错，但看到了关键证据（没认出来）" if t["看到关键证据"] else "错，也没看到关键证据（没查到）"


def summary(rows):
    n = len(rows)
    q = Counter(quadrant(t) for t in rows.values())
    return {"条数": n, "第1轮看现场": sum(t["第1轮看现场"] for t in rows.values()),
            "第1轮调知识库": sum("search_notes" in t["第1轮"] for t in rows.values()),
            "知识库先于有效现场": sum(t["知识库先于有效现场"] for t in rows.values()),
            "看到关键证据": sum(t["看到关键证据"] for t in rows.values()),
            "提议堆转储": sum(1 for t in rows.values() if t["提议堆转储"]),
            "有重复调用": sum(1 for t in rows.values() if t["重复调用"]),
            "平均轮数": round(sum(t["轮数"] for t in rows.values()) / n, 1),
            "通过": sum(t["通过"] for t in rows.values()), "四格": dict(q)}


KEYS = {c["id"]: key_tools(c) for c in harness.CASES}

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "keys":
        cnt = Counter()
        for cid, k in KEYS.items():
            cnt.update(k or {"（现场里没有说中词）"})
            print(f"{cid:22} {', '.join(sorted(k)) or '（现场里没有说中词）'}")
        print("\n关键证据所在的工具：" + "，".join(f"{k} {v}" for k, v in cnt.most_common()))
    elif cmd == "judge":
        rows = load(sys.argv[2])
        for cid, t in rows.items():
            print(f"{cid:22} {'✓' if t['通过'] else '✕'} 第1轮 {t['第1轮']:26} 关键证据 {'看到' if t['看到关键证据'] else '没看到':3}  {t['序列'][:70]}")
        s = summary(rows)
        print("\n" + json.dumps(s, ensure_ascii=False, indent=1))
        (HERE / "runs").mkdir(exist_ok=True)
        (HERE / "runs" / f"{sys.argv[2]}.json").write_text(json.dumps({"summary": s, "cases": rows}, ensure_ascii=False, indent=1))
    elif cmd == "compare":
        a, b = load(sys.argv[2]), load(sys.argv[3])
        sa, sb = summary(a), summary(b)
        for k in sa:
            if k != "四格":
                print(f"{k:10} {sa[k]!s:>6} → {sb[k]!s:<6}")
        print("\n四格：")
        for k in sorted(set(sa["四格"]) | set(sb["四格"])):
            print(f"  {k:20} {sa['四格'].get(k, 0):3} → {sb['四格'].get(k, 0)}")
        moved = Counter((quadrant(a[c]), quadrant(b[c])) for c in a if c in b and quadrant(a[c]) != quadrant(b[c]))
        print("\n换了格子的：")
        for (x, y), n in moved.most_common():
            print(f"  {n:2} 条  {x} → {y}")
    elif cmd == "show":
        rows = load(sys.argv[2])
        cid = next(c for c in rows if c.startswith(sys.argv[3]))
        print(cid, json.dumps(rows[cid], ensure_ascii=False, indent=1), "关键工具", KEYS[cid])
    else:
        print(__doc__)
