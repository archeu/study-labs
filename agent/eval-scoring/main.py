"""怎么打分：同一批回答，用三种判法各判一遍，再和人工标注逐条比。

    uv run python eval-scoring/main.py judge base-@3-r1                         # LLM 评委（带参考答案）判 50 条
    uv run python eval-scoring/main.py judge base-@3-r1 --tag again             # 原样再判一遍：评委自己稳不稳
    uv run python eval-scoring/main.py judge base-@3-r1 --no-ref --tag noref    # 不给参考答案
    uv run python eval-scoring/main.py agree base-@3-r1                         # 规则、评委、人工标注逐条对照

被判的回答来自 eval-dataset 的一次运行（runs/<名字>/）。评委默认用同一个本机模型（Qwen3-1.7B，关思考），
这是故意的：小模型当评委会出什么事，正是这一页要看的。连 Claude 时设 ANTHROPIC_BASE_URL= 和 MODEL。
"""

import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "eval-dataset"))
import harness  # noqa: E402
from refs import REF  # noqa: E402

JUDGE_SYSTEM = """你是值班排障回答的评审。只判一件事：助手给出的根本原因，和参考答案说的是不是同一件事。
- 对：根因和参考答案一致（措辞不同没关系）。
- 部分对：提到了真正的原因，但又把主因归到了别处；或者只说了现象，没说根因。
- 错：根因不同，或者没有给出根因。
先用一两句话说理由，最后一行只写 JSON：{"verdict": "对"} 或 {"verdict": "部分对"} 或 {"verdict": "错"}"""

JUDGE_SYSTEM_NOREF = """你是值班排障回答的评审。判断助手找到的根本原因是否正确、是否有证据支撑。
- 对：原因找对了，证据充分。
- 部分对：方向对但不完整，或证据不足。
- 错：原因不对，或者没有给出原因。
先用一两句话说理由，最后一行只写 JSON：{"verdict": "对"} 或 {"verdict": "部分对"} 或 {"verdict": "错"}"""

SCORE = {"对": 1.0, "部分对": 0.5, "错": 0.0}


def judge_one(question, answer, ref=None):
    if ref is None:
        system, user = JUDGE_SYSTEM_NOREF, f"告警：{question}\n\n助手的回答：\n{answer[:1500]}"
    else:
        system, user = JUDGE_SYSTEM, f"告警：{question}\n\n参考答案（真实原因）：{ref}\n\n助手的回答：\n{answer[:1500]}"
    extra = {"chat_template_kwargs": {"enable_thinking": False}} if harness.LOCAL else {}
    t0 = time.perf_counter()
    r = harness.client.messages.create(model=harness.MODEL, max_tokens=400, system=system,
                                       messages=[{"role": "user", "content": user}], extra_body=extra)
    text = "".join(b.text for b in r.content if b.type == "text").strip()
    found = re.findall(r'"verdict"\s*:\s*"(对|部分对|错)"', text)
    return {"verdict": found[-1] if found else "解析失败", "reason": text[:300], "ms": round((time.perf_counter() - t0) * 1000),
            "input_tokens": r.usage.input_tokens, "output_tokens": r.usage.output_tokens}


def load_answers(run):
    out = {}
    for f in sorted((harness.RUNS / run).glob("[0-9][0-9]-*.json")):
        rec = json.loads(f.read_text())
        out[rec["case"]] = {"answer": harness.final_answer(rec["messages"]), "grade": rec["grade"]}
    return out


def cmd_judge(run, tag, use_ref, only):
    cases = {c["id"]: c for c in harness.CASES}
    answers = load_answers(run)
    rows = {}
    for cid, a in answers.items():
        if only and not any(cid.startswith(o) for o in only):
            continue
        j = judge_one(cases[cid]["question"], a["answer"], REF[cid[:2]] if use_ref else None)
        rows[cid] = j
        print(f"{cid:22} {j['verdict']:4} {j['ms'] / 1000:5.1f}s  {j['reason'][:70].replace(chr(10), ' ')}", flush=True)
    out = HERE / "runs" / f"{run}-judge{'-' + tag if tag else ''}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    print(f"\n写到 {out.relative_to(HERE.parent)}")


def table(pairs, name_a, name_b):
    """两种判法逐条对照：都说对、都说错、A 对 B 错、A 错 B 对。"""
    both1 = sum(1 for a, b in pairs if a and b)
    both0 = sum(1 for a, b in pairs if not a and not b)
    a1b0 = sum(1 for a, b in pairs if a and not b)
    a0b1 = sum(1 for a, b in pairs if not a and b)
    n = len(pairs)
    print(f"{name_a} 对 {name_b}（{n} 条）：一致 {both1 + both0}（都算对 {both1}、都算错 {both0}），"
          f"{name_a} 算对而 {name_b} 算错 {a1b0}，{name_a} 算错而 {name_b} 算对 {a0b1}")
    return {"n": n, "agree": both1 + both0, "both1": both1, "both0": both0, "a1b0": a1b0, "a0b1": a0b1}


def cmd_agree(run):
    from labels import HUMAN   # 人工标注：读对话判的“原因对不对”，1 / 0.5 / 0
    answers = load_answers(run)
    jdir = HERE / "runs"
    judge = json.loads((jdir / f"{run}-judge.json").read_text())
    again = json.loads((jdir / f"{run}-judge-again.json").read_text()) if (jdir / f"{run}-judge-again.json").exists() else {}
    noref = json.loads((jdir / f"{run}-judge-noref.json").read_text()) if (jdir / f"{run}-judge-noref.json").exists() else {}
    ids = [c for c in answers if c[:2] in HUMAN]
    human = {c: HUMAN[c[:2]] >= 1 for c in ids}
    rule = {c: answers[c]["grade"]["说中"] and not answers[c]["grade"]["怪错"] for c in ids}
    jd = {c: SCORE.get(judge[c]["verdict"], 0) >= 1 for c in ids if c in judge}
    res = {"规则": table([(rule[c], human[c]) for c in ids], "规则", "人工"),
           "评委": table([(jd[c], human[c]) for c in ids if c in jd], "评委", "人工")}
    if noref:
        nr = {c: SCORE.get(noref[c]["verdict"], 0) >= 1 for c in noref}
        res["无参考评委"] = table([(nr[c], human[c]) for c in nr if c in human], "无参考评委", "人工")
    if again:
        print(f"评委原样再判一遍：{sum(1 for c in again if c in judge and again[c]['verdict'] != judge[c]['verdict'])} / {len(again)} 条结论变了")
    fails = [c for c in ids if c in jd and jd[c] != human[c]]
    print("\n评委和人工不一致的：")
    for c in fails:
        print(f"  {c:22} 评委 {judge[c]['verdict']:3} 人工 {HUMAN[c[:2]]}  | {answers[c]['answer'][:80].replace(chr(10), ' ')}")
    rfails = [c for c in ids if rule[c] != human[c]]
    print("\n规则和人工不一致的：")
    for c in rfails:
        print(f"  {c:22} 规则 {'对' if rule[c] else '错'} 人工 {HUMAN[c[:2]]}  说中={answers[c]['grade']['说中']} 怪错={answers[c]['grade']['怪错']}")
    (jdir / f"{run}-agree.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))


def cmd_dump(run, width=260):
    """把每条回答的“原因”那段打出来，供人工标注时读。"""
    for cid, a in load_answers(run).items():
        print(f"## {cid}  参考：{REF[cid[:2]][:60]}\n{harness.cause_part(a['answer'])[:width]}\n")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    tag = sys.argv[sys.argv.index("--tag") + 1] if "--tag" in sys.argv else ""
    only = sys.argv[sys.argv.index("--only") + 1:] if "--only" in sys.argv else None
    if cmd == "judge":
        cmd_judge(sys.argv[2], tag, "--no-ref" not in sys.argv, only)
    elif cmd == "agree":
        cmd_agree(sys.argv[2])
    elif cmd == "dump":
        cmd_dump(sys.argv[2])
    else:
        print(__doc__)
