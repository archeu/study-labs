"""为什么必须评测：亲眼看到两件事。

1. 输出不确定：同一版提示词、同一条用例，跑几遍结果不一样；整套 10 条再跑一遍，有几条会翻面。
2. 改一处坏一片：站 04 只改了工具说明（v1 → v2），10 条里有的变好，有的出了新毛病。

    uv run python eval-why/main.py same 02 --times 5     # 用例 02 原样跑 5 遍（v2，本机约 5 分钟）
    uv run python eval-why/main.py repeat                # 10 条 v2 原样再跑 1 遍（约 15 分钟），和 11-24 那遍逐条对照
    uv run python eval-why/main.py diff ../eval-first-cases/runs/2026-11-24 ../eval-first-cases/runs/2026-11-24-v2
    uv run python eval-why/main.py diff ../eval-first-cases/runs/2026-11-24-v2 runs/2026-11-26/repeat

被测系统、用例、判分全部复用 eval-first-cases（站 04 那页），这里一行都不改，只是多跑几遍、逐条比。
"""

import importlib.util
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
EFC = HERE.parent / "eval-first-cases"


def load_harness():
    """按 v2（工具说明改过的那版）加载 eval-first-cases/main.py。它在 import 时读 sys.argv，所以临时换掉。"""
    sys.path.insert(0, str(EFC))
    argv, sys.argv = sys.argv, [str(EFC / "main.py"), "--v2"]
    spec = importlib.util.spec_from_file_location("efc", EFC / "main.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    sys.argv = argv
    return mod


def tools_of(messages):
    return [b["name"] for m in messages if m["role"] == "assistant" for b in m["content"] if b["type"] == "tool_use"]


def why_failed(g):
    return ("空回答" if g["空回答"] else "没看现场" if not g["看过现场"] else "没说中" if not g["说中"]
            else f"怪错{g['怪错']}" if g["怪错"] else f"编造{g['编造数字']}" if g["编造数字"] else "")


def run_once(h, case, out: Path, name: str):
    h.setup(case)
    t0 = time.perf_counter()
    messages, usage = h.run_agent(case["question"])
    dt = time.perf_counter() - t0
    g, answer = h.grade(case, messages)
    g.update({"id": case["id"], "秒": round(dt, 1), "token": usage, "工具": tools_of(messages)})
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{name}.json").write_text(json.dumps(messages, ensure_ascii=False, indent=1))
    return g, answer


def cmd_same(prefix: str, times: int):
    h = load_harness()
    case = next(c for c in h.CASES if c["id"].startswith(prefix))
    out = HERE / "runs" / time.strftime("%Y-%m-%d") / f"same-{case['id']}"
    rows = []
    for k in range(1, times + 1):
        g, answer = run_once(h, case, out, f"{k}")
        rows.append(g)
        print(f"第 {k} 遍 {'✓' if g['通过'] else '✕'} {g['轮数']} 轮 {g['秒']:5.1f}s {why_failed(g):12} 工具 {'→'.join(g['工具'])}"
              f"\n        回答开头：{answer[:70].replace(chr(10), ' ')}", flush=True)
    (out / "scores.json").write_text(json.dumps({"case": case["id"], "runs": rows}, ensure_ascii=False, indent=1))
    print(f"\n{case['id']}：{times} 遍里通过 {sum(r['通过'] for r in rows)} 遍")


def cmd_repeat():
    h = load_harness()
    out = HERE / "runs" / time.strftime("%Y-%m-%d") / "repeat"
    rows, t_all = [], time.perf_counter()
    for case in h.CASES:
        g, answer = run_once(h, case, out, case["id"])
        rows.append(g)
        print(f"{'✓' if g['通过'] else '✕'} {case['id']:20} {g['轮数']} 轮 {g['秒']:5.1f}s {why_failed(g)}", flush=True)
    summary = h.summarize(rows, round(time.perf_counter() - t_all))
    (out / "scores.json").write_text(json.dumps({"summary": summary, "cases": rows}, ensure_ascii=False, indent=1))
    print("\n" + json.dumps(summary, ensure_ascii=False))


FIELDS = ["通过", "看过现场", "说中", "怪错", "编造数字"]
GOOD_WHEN_TRUE = {"通过": True, "看过现场": True, "说中": True, "怪错": False, "编造数字": False}


def cmd_diff(a: str, b: str):
    """两份 scores.json 逐条对照：每个指标谁变好、谁变坏。"""
    A = {r["id"]: r for r in json.loads((Path(a) / "scores.json").read_text())["cases"]}
    B = {r["id"]: r for r in json.loads((Path(b) / "scores.json").read_text())["cases"]}
    print(f"A = {a}\nB = {b}\n")
    print(f"{'用例':20}" + "".join(f"{f:>8}" for f in FIELDS))
    better, worse = {f: [] for f in FIELDS}, {f: [] for f in FIELDS}
    for cid in A:
        if cid not in B:
            continue
        cells = []
        for f in FIELDS:
            x, y = bool(A[cid][f]), bool(B[cid][f])
            mark = lambda v: "✓" if v == GOOD_WHEN_TRUE[f] else "✕"   # noqa: E731
            if x != y:
                (better if y == GOOD_WHEN_TRUE[f] else worse)[f].append(cid[:2])
            cells.append(f"{mark(x)}→{mark(y)}" if x != y else f"  {mark(x)} ")
        print(f"{cid:20}" + "".join(f"{c:>8}" for c in cells))
    print()
    for f in FIELDS:
        print(f"{f:6} 变好 {len(better[f])} 条 {better[f]}  变坏 {len(worse[f])} 条 {worse[f]}")
    flips = sum(len(better[f]) + len(worse[f]) for f in ("通过",))
    print(f"\n“通过”翻面 {flips} 条；A 通过 {sum(bool(r['通过']) for r in A.values())}，B 通过 {sum(bool(r['通过']) for r in B.values())}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "same":
        cmd_same(sys.argv[2], int(sys.argv[sys.argv.index("--times") + 1]) if "--times" in sys.argv else 5)
    elif cmd == "repeat":
        cmd_repeat()
    elif cmd == "diff":
        cmd_diff(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
