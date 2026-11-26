"""提示词和 Skill 的版本管理：版本不可变、内容有指纹、线上用标签指向版本，整套配置打成一个发布包。

    uv run python prompt-versioning/main.py list                 # 有哪些版本，各自的指纹
    uv run python prompt-versioning/main.py lock                 # 新版本登记进 prompts.lock.json（已登记的不许改）
    uv run python prompt-versioning/main.py check                # CI 里跑：已发布的版本被改了没有、标签指向的版本在不在
    uv run python prompt-versioning/main.py diff @3 @4
    uv run python prompt-versioning/main.py label canary @5      # 标签指向新版本（灰度）；production 同理
    uv run python prompt-versioning/main.py rollback production  # 标签退回上一个版本
    uv run python prompt-versioning/main.py bundle               # 发布包：系统提示词 + 工具说明 + Skill + 模型参数，一个指纹
    uv run python prompt-versioning/main.py demo                 # 在临时目录里演示：原地改了 @3 会被 check 抓住
    uv run python prompt-versioning/main.py traces ../agent-tracing/traces/base-@3-r1.jsonl   # span 上记的版本

提示词文件放在 eval-dataset/prompts/（站 09 的评测运行器从那里读），文件名就是版本：oncall-system@3.md。
"""

import copy
import difflib
import hashlib
import json
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "eval-dataset"))
PROMPTS = Path(os.environ.get("PROMPTS_DIR", HERE.parent / "eval-dataset" / "prompts"))
STATE = Path(os.environ.get("STATE_DIR", HERE))
SKILL = HERE / "skills" / "oncall-triage"


def sha(data: str | bytes) -> str:
    return hashlib.sha256(data.encode() if isinstance(data, str) else data).hexdigest()[:12]


def files():
    return {p.stem: p for p in sorted(PROMPTS.glob("*@*.md"))}          # "oncall-system@3" -> Path


def lockfile():
    p = STATE / "prompts.lock.json"
    return json.loads(p.read_text()) if p.exists() else {}


def labels():
    p = STATE / "labels.json"
    return json.loads(p.read_text()) if p.exists() else {"oncall-system": {"production": "@3", "history": []}}


def save(name, data):
    (STATE / name).write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")


def load(name="oncall-system", version=None, label=None):
    """线上读提示词：给标签就按标签找版本。返回原文、版本、指纹，后两样要写进每个 span。"""
    if label:
        version = labels()[name][label]
    text = (PROMPTS / f"{name}{version}.md").read_text().strip()
    return {"name": name, "version": version, "sha": sha(text), "text": text}


def cmd_list():
    lk = lockfile()
    for key, p in files().items():
        s = sha(p.read_text().strip())
        state = "已登记" if lk.get(key) == s else ("内容和登记的不一样！" if key in lk else "未登记")
        print(f"{key:20} {s}  {state}")
    print("\n标签：" + json.dumps({k: {l: v for l, v in d.items() if l != 'history'} for k, d in labels().items()}, ensure_ascii=False))


def cmd_lock():
    lk = lockfile()
    for key, p in files().items():
        s = sha(p.read_text().strip())
        if key in lk and lk[key] != s:
            sys.exit(f"拒绝：{key} 已经发布过（{lk[key]}），内容却变了（{s}）。要改就另起一个新版本号。")
        if key not in lk:
            lk[key] = s
            print(f"登记 {key} {s}")
    save("prompts.lock.json", lk)


def cmd_check() -> int:
    """CI 里跑。返回错误条数。"""
    lk, errs = lockfile(), []
    fs = files()
    for key, p in fs.items():
        s = sha(p.read_text().strip())
        if key not in lk:
            errs.append(f"{key} 还没登记：先跑 lock，再提交")
        elif lk[key] != s:
            errs.append(f"{key} 已发布的版本被原地改了（登记 {lk[key]}，现在 {s}）：改回去，另起新版本号")
    for key in lk:
        if key not in fs:
            errs.append(f"{key} 登记过但文件没了：已发布的版本不能删，线上和历史 trace 还在引用它")
    for name, d in labels().items():
        for label, v in d.items():
            if label != "history" and f"{name}{v}" not in fs:
                errs.append(f"标签 {name}:{label} 指向不存在的 {v}")
    print("\n".join(errs) or "check 通过：已发布的版本都没被改，标签都指向存在的版本")
    return len(errs)


def cmd_diff(a, b, name="oncall-system"):
    x = (PROMPTS / f"{name}{a}.md").read_text().splitlines()
    y = (PROMPTS / f"{name}{b}.md").read_text().splitlines()
    print("\n".join(difflib.unified_diff(x, y, f"{name}{a}", f"{name}{b}", lineterm="", n=0)))


def cmd_label(label, version, name="oncall-system", who=os.environ.get("USER", "?")):
    lb = labels()
    d = lb.setdefault(name, {"history": []})
    if f"{name}{version}" not in lockfile():
        sys.exit(f"拒绝：{name}{version} 没登记，不能上线")
    d.setdefault("history", []).append({"time": time.strftime("%Y-%m-%d %H:%M"), "label": label, "from": d.get(label), "to": version, "who": who})
    d[label] = version
    save("labels.json", lb)
    print(f"{name}:{label} → {version}")


def cmd_rollback(label, name="oncall-system"):
    lb = labels()
    hist = [h for h in lb[name]["history"] if h["label"] == label and h["from"]]
    if not hist:
        sys.exit(f"{label} 没有可以退回的版本")
    cmd_label(label, hist[-1]["from"], name, who="rollback")


def bundle(tools_variant="v2", prompt_version=None):
    """发布包：影响行为的东西全列进来，算一个指纹。系统提示词没变，工具说明变了，包的指纹也会变。"""
    import harness
    import oncall
    tools = harness.tools_v2() if tools_variant == "v2" else copy.deepcopy(oncall.TOOLS)
    p = load(version=prompt_version) if prompt_version else load(label="production")
    skill = {f.relative_to(SKILL).as_posix(): sha(f.read_bytes()) for f in sorted(SKILL.rglob("*")) if f.is_file()}
    manifest = {"system_prompt": f"{p['name']}{p['version']}#{p['sha']}",
                "tools": f"tools-{tools_variant}#{sha(json.dumps(tools, ensure_ascii=False, sort_keys=True))}",
                "skill": f"oncall-triage#{sha(json.dumps(skill, sort_keys=True))}",
                "model": harness.MODEL, "temperature": 0.8, "thinking": False, "max_steps": harness.MAX_STEPS}
    manifest["bundle"] = sha(json.dumps(manifest, sort_keys=True))
    return manifest


def cmd_demo():
    """在临时目录里演示，不碰真文件：登记 → 原地改 @3 → check 抓住 → 正确做法是另起 @6。"""
    global PROMPTS, STATE
    tmp = Path(tempfile.mkdtemp(prefix="prompt-demo-"))
    try:
        shutil.copytree(PROMPTS, tmp / "prompts")
        PROMPTS, STATE = tmp / "prompts", tmp
        print("① 登记现有版本"); cmd_lock()
        print("\n② 有人图省事，直接在 @3 里加了一句，没改版本号")
        p = PROMPTS / "oncall-system@3.md"
        p.write_text(p.read_text().rstrip() + "\n回答尽量简短。\n")
        print("check →"); n = cmd_check()
        print(f"（CI 里返回 {n} 条错误，PR 变红）")
        print("\n③ 正确做法：@3 改回去，新内容另起 @6，登记，再走评测")
        p.write_text(p.read_text().replace("\n回答尽量简短。\n", "\n"))
        (PROMPTS / "oncall-system@6.md").write_text(p.read_text().rstrip() + "\n回答尽量简短。\n")
        cmd_lock(); print("check →"); cmd_check()
        print("\n④ 标签：canary 指 @6，production 还是 @3；出事一条命令退回")
        cmd_label("canary", "@6"); cmd_label("production", "@6"); cmd_rollback("production")
        print("labels.json 里的历史：")
        for h in labels()["oncall-system"]["history"]:
            print("  ", h)
    finally:
        shutil.rmtree(tmp)


def cmd_traces(path):
    from collections import Counter
    c = Counter()
    for line in Path(path).read_text().splitlines():
        s = json.loads(line)
        a = s["attributes"]
        if a.get("gen_ai.operation.name") == "invoke_agent":
            c[(a.get("gen_ai.prompt.name"), a.get("gen_ai.prompt.version"), a.get("oncall.prompt.sha"), a.get("eval.passed"))] += 1
    for (n, v, s, ok), k in sorted(c.items(), key=str):
        print(f"{n}{v} #{s}  {'通过' if ok else '没过'} {k} 条")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    if cmd == "list":
        cmd_list()
    elif cmd == "lock":
        cmd_lock()
    elif cmd == "check":
        sys.exit(1 if cmd_check() else 0)
    elif cmd == "diff":
        cmd_diff(sys.argv[2], sys.argv[3])
    elif cmd == "label":
        cmd_label(sys.argv[2], sys.argv[3])
    elif cmd == "rollback":
        cmd_rollback(sys.argv[2])
    elif cmd == "bundle":
        for v in ("v1", "v2"):
            print(f"工具说明 {v}：" + json.dumps(bundle(v, "@3"), ensure_ascii=False))
    elif cmd == "demo":
        cmd_demo()
    elif cmd == "traces":
        cmd_traces(sys.argv[2])
    else:
        print(__doc__)
