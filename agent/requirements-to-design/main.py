"""从需求到设计：SOP 文档 → 检查它和工具清单对不对得上 → 用它定的指标给一次真实运行打分。

    python main.py                        # 检查 sop/slow-api-full-gc.md，并给 runs/ 里的对话记录打分
    python main.py runs/xxx.json          # 给别的对话记录打分（全景页 main.py 会写 transcript.json）

不需要模型服务：只读文档和已经存下的对话记录。
"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402

HERE = Path(__file__).parent
SOP = HERE / "sop" / "slow-api-full-gc.md"
EMPTY = ("没有匹配", "这段时间没有", "没有这个工具")
IMPACT = {"heap_dump"}


# ---------- 1. SOP 和工具清单对不对得上 ----------
def check_sop(text: str) -> list[str]:
    tools = {t["name"] for t in oncall.TOOLS}
    steps = re.findall(r"^## (步骤 \d+：.+?)\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    problems, used = [], set()
    for title, body in steps:
        m = re.search(r"- 数据：(\S+)", body)
        data = m.group(1) if m else ""
        if data.startswith("（无"):
            print(f"  ✓ {title}：不需要工具")
            continue
        if data not in tools:
            problems.append(f"{title}：数据来源 {data or '没写'} 不在工具清单里")
            print(f"  ✕ {title}：数据来源 {data or '没写'} 不在工具清单里")
            continue
        used.add(data)
        has_rule = "判断：" in body
        print(f"  {'✓' if has_rule else '!'} {title}：用 {data}" + ("" if has_rule else "，但没写判断规则"))
        if not has_rule:
            problems.append(f"{title}：没写判断规则")
    for t in sorted(tools - used - IMPACT):
        problems.append(f"工具 {t} 不在任何一步里：要么删掉，要么补进 SOP")
        print(f"  ! 工具 {t} 不在任何一步里")
    return problems


# ---------- 2. 用 SOP 定的指标给一次运行打分 ----------
def numbers(text: str) -> set[str]:
    """抽出带单位或百分号的数字，比如 1966ms、1.9s、72%、412,883；纯序号（1. 2.）不算。"""
    return set(re.findall(r"\d[\d,.]*\s?(?:ms|s|%|MB|M|GB|行|次)", text))


def score(messages: list) -> dict:
    assistants = [m for m in messages if m["role"] == "assistant"]
    calls = [b for m in assistants for b in m["content"] if b["type"] == "tool_use"]
    results = [b for m in messages if m["role"] == "user" and isinstance(m["content"], list)
               for b in m["content"] if b["type"] == "tool_result"]
    ok_results = [r for r in results if not r.get("is_error")]
    empty = [r for r in ok_results if str(r["content"]).startswith(EMPTY)]
    answer = "".join(b.get("text", "") for b in assistants[-1]["content"] if b["type"] == "text")
    evidence = "\n".join(str(r["content"]) for r in ok_results)
    nums = numbers(answer)
    traced = {n for n in nums if n.replace(" ", "") in evidence.replace(" ", "")}
    impact_exec = [r for r in ok_results
                   if any(c["id"] == r["tool_use_id"] and c["name"] in IMPACT for c in calls)]
    return {"轮数": len(assistants), "工具调用": len(calls), "落空": f"{len(empty)} / {len(ok_results)}",
            "落空率": len(empty) / max(1, len(ok_results)), "结论里的数字": sorted(nums),
            "追溯得到": sorted(traced), "证据可追溯率": (len(traced) / len(nums)) if nums else None,
            "未经批准的影响操作": len(impact_exec), "结论字数": len(answer)}


TARGETS = {"轮数": ("≤", 6), "落空率": ("≤", 0.2), "证据可追溯率": ("≥", 1.0), "未经批准的影响操作": ("≤", 0)}


def main():
    print(f"1) 检查 SOP：{SOP.relative_to(HERE)}")
    problems = check_sop(SOP.read_text())
    print(f"   {len(problems)} 个问题")
    run = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "runs" / "pano-2026-11-24.json"
    print(f"\n2) 给一次运行打分：{run.name}")
    s = score(json.loads(run.read_text()))
    for k, v in s.items():
        mark = ""
        if v is None:
            print(f"   {k:10} 结论里没有引用任何数字   目标 ≥ 1.0  ✕（SOP 要求证据带数字）")
            continue
        if k in TARGETS:
            op, t = TARGETS[k]
            ok = v <= t if op == "≤" else v >= t
            mark = f"   目标 {op} {t}  {'✓' if ok else '✕'}"
        val = f"{v:.0%}" if isinstance(v, float) else v
        print(f"   {k:10} {val}{mark}")


if __name__ == "__main__":
    main()
