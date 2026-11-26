"""换模型怎么回归：四组运行（模型 × 提示词）的总表，以及任意两组之间的逐条配对。

    uv run python model-regression/main.py table
    uv run python model-regression/main.py pair base-@3-r1 tuned-@6-qwen06-r1
    uv run python model-regression/main.py pair tuned-@6-qwen17-r1 tuned-@6-qwen06-r1    # 同一提示词，只换模型

运行本身用 eval-dataset：换模型就是换 MODEL 环境变量和本机加载的模型文件，例如
    MODEL=qwen3-0.6b uv run python eval-dataset/run.py run --prompt @6 --name tuned-@6-qwen06-r1
“看到关键证据”来自 eval-trajectory 的 judge 结果（先跑 eval-trajectory/main.py judge <run>）。
"""

import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUNS = HERE.parent / "eval-dataset" / "runs"
TRAJ = HERE.parent / "eval-trajectory" / "runs"
GROUPS = [("1.7B + @3（线上基线）", "base-@3-r1"), ("0.6B + @3（直接换）", "base-@3-qwen06-r1"),
          ("1.7B + @6（只换提示词）", "tuned-@6-qwen17-r1"), ("0.6B + @6", "tuned-@6-qwen06-r1")]
METRICS = [("通过", "通过", True), ("说中", "说中", True), ("怪错", "没怪错", False), ("编造数字", "没编造数字", False), ("看过现场", "看过现场", True)]


def rows(run):
    return {r["id"]: r for r in json.loads((RUNS / run / "scores.json").read_text())["cases"]}


def summary(run):
    return json.loads((RUNS / run / "scores.json").read_text())["summary"]


def traj(run):
    p = TRAJ / f"{run}.json"
    return json.loads(p.read_text())["cases"] if p.exists() else None


def table():
    print(f"{'组':22} 通过 说中 怪错 看过现场 关键证据 轮数  耗时  输出token")
    for name, run in GROUPS:
        s, t = summary(run), traj(run)
        key = sum(v["看到关键证据"] for v in t.values()) if t else "-"
        print(f"{name:22} {s['通过']:4} {s['说中']:4} {s['怪错']:4} {s['看过现场']:8} {key!s:8} {s['平均轮数']:4} {s['平均秒']:5}s {s['平均输出token']:6}")


def pair(a, b):
    A, B = rows(a), rows(b)
    print(f"{a} → {b}（变坏 b / 变好 c，噪声线 2√(b+c)）")
    for key, label, good in METRICS:
        bb = sum(1 for c in A if bool(A[c][key]) == good and bool(B[c][key]) != good)
        cc = sum(1 for c in A if bool(A[c][key]) != good and bool(B[c][key]) == good)
        line = 2 * math.sqrt(bb + cc)
        tag = "显著变坏" if bb - cc > line else "显著变好" if cc - bb > line else "分不出"
        print(f"  {label:6} b {bb:2}  c {cc:2}  净 {bb - cc:+3}  线 {line:4.1f}  {tag}")
    TA, TB = traj(a), traj(b)
    if TA and TB:
        bb = sum(1 for c in TA if TA[c]["看到关键证据"] and not TB[c]["看到关键证据"])
        cc = sum(1 for c in TA if not TA[c]["看到关键证据"] and TB[c]["看到关键证据"])
        line = 2 * math.sqrt(bb + cc)
        tag = "显著变坏" if bb - cc > line else "显著变好" if cc - bb > line else "分不出"
        print(f"  关键证据 b {bb:2}  c {cc:2}  净 {bb - cc:+3}  线 {line:4.1f}  {tag}")
    sa, sb = summary(a), summary(b)
    print(f"  输出 token ×{sb['平均输出token'] / sa['平均输出token']:.2f}，耗时 ×{sb['平均秒'] / sa['平均秒']:.2f}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "table":
        table()
    elif cmd == "pair":
        pair(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
