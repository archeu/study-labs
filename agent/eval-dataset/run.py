"""评测集：50 条用例怎么来的、长什么样，跑一遍值班助手看分布。

    uv run python eval-dataset/run.py stats                       # 不跑模型：按类型、来源数一遍，检查覆盖
    uv run python eval-dataset/run.py run --prompt @3             # 50 条全跑（本机 Qwen3-1.7B 约一小时）
    uv run python eval-dataset/run.py run --prompt @3 --only 02 11 --name try
    uv run python eval-dataset/run.py report <run 名>             # 按类型、来源看通过率、失败原因
    uv run python eval-dataset/run.py regrade <run 名>            # 判据改了：按存下的对话重判，不重跑模型
    ANTHROPIC_BASE_URL= ANTHROPIC_API_KEY=sk-ant-... MODEL=claude-sonnet-5-5 uv run python eval-dataset/run.py run --prompt @3

结果写进 runs/<名字>/：每条一个 JSON（对话、每次调用的耗时和 token、判分），scores.json 是汇总。
"""

import json
import sys
import time
from collections import Counter, defaultdict

import harness


def arg(flag, default=None):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default


def stats():
    cases = harness.CASES
    print(f"共 {len(cases)} 条\n")
    for key, label in (("cat", "故障类型"), ("src", "来源")):
        cnt = Counter(c[key] for c in cases)
        print(f"按{label}：" + "，".join(f"{k} {v}" for k, v in cnt.most_common()))
    edge = [c["id"] for c in cases if c.get("tool_error") or c.get("must_not_call")]
    print(f"\n带“工具坏了”或“不许调用”的：{len(edge)} 条 {edge}")
    words = Counter(w for c in cases for w in c["must_not"])
    print("“原因里不许出现”最常见的词：" + "，".join(f"{w}×{n}" for w, n in words.most_common(5)))
    print(f"问题里不是 P99 变慢的：{sum('P99' not in c['question'] for c in cases)} 条")
    # 判据体检：“说中”的词如果在问题或知识库里就有，不看现场也说得出，会判出假通过
    kb = " ".join(t + s for _, t, s in harness.oncall.NOTES).lower()
    print("\n判据体检（说中词出现在问题或知识库里）：")
    for c in cases:
        leak = [w for w in c["expect_any"] if w.lower() in kb or w.lower() in c["question"].lower()]
        if leak:
            safe = [w for w in c["expect_any"] if w not in leak]
            print(f"  {c['id']:20} 白送的词 {leak}  只在现场里的词 {safe}")


def report(name):
    data = harness.load_scores(name)
    s, rows = data["summary"], data["cases"]
    print(json.dumps({k: v for k, v in s.items() if k != "按类型"}, ensure_ascii=False))
    print("\n按类型：")
    for cat, v in s["按类型"].items():
        print(f"  {cat:8} {v}")
    by_src = defaultdict(lambda: [0, 0])
    for r in rows:
        by_src[r["src"]][0] += r["通过"]
        by_src[r["src"]][1] += 1
    print("按来源：" + "，".join(f"{k} {a}/{b}" for k, (a, b) in by_src.items()))
    reasons = Counter(harness.why(r).split("[")[0] or "通过" for r in rows)
    print("失败原因：" + "，".join(f"{k} {v}" for k, v in reasons.most_common()))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "stats":
        stats()
    elif cmd == "run":
        version = arg("--prompt", "@3")
        name = arg("--name", f"{time.strftime('%Y-%m-%d')}-{version}-{harness.MODEL}")
        only = sys.argv[sys.argv.index("--only") + 1:] if "--only" in sys.argv else None
        summary = harness.run_suite(version, name, only, thinking="--thinking" in sys.argv)
        print("\n" + json.dumps(summary, ensure_ascii=False))
    elif cmd == "report":
        report(sys.argv[2])
    elif cmd == "regrade":
        print(json.dumps(harness.regrade(sys.argv[2]), ensure_ascii=False))
    else:
        print(__doc__)
