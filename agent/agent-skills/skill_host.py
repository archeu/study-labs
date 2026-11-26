"""迷你 Skill 主机：给值班助手加上“按需加载的说明书”。不调模型，模型的那几次决定写死，重点看每一层进上下文的有多少字。

    uv run python agent-skills/skill_host.py

三层：
  第 1 层  启动时：每个 Skill 只把 name + description 放进系统提示
  第 2 层  模型觉得相关时：调 load_skill(name)，SKILL.md 正文进上下文
  第 3 层  正文里点名的文件：要读才读（read_skill_file）；脚本直接跑（run_skill_script），只有输出进上下文
另外按 Agent Skills 规范检查 frontmatter：name 只能小写字母、数字、连字符，1～64 字，和目录同名；description 1～1024 字。
"""

import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKILLS = HERE / "skills"
sys.path.insert(0, str(HERE.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402  现场数据，喂给脚本用


def parse(skill_dir: Path) -> dict:
    text = (skill_dir / "SKILL.md").read_text(encoding="utf-8")
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.S)
    if not m:
        raise ValueError(f"{skill_dir.name}: SKILL.md 开头没有 --- frontmatter ---")
    meta = dict(re.findall(r"^([a-z][a-z-]*):\s*(.*)$", m.group(1), re.M))
    problems = []
    name, desc = meta.get("name", ""), meta.get("description", "")
    if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", name) or len(name) > 64:
        problems.append(f"name {name!r} 不合规：只能小写字母、数字、单个连字符，1～64 字")
    if name != skill_dir.name:
        problems.append(f"name {name!r} 和目录名 {skill_dir.name!r} 不一致")
    if not 1 <= len(desc) <= 1024:
        problems.append(f"description {len(desc)} 字，要求 1～1024")
    return {"dir": skill_dir, "name": name, "description": desc, "body": m.group(2), "problems": problems}


def level1(skills: list[dict]) -> str:
    """系统提示里的那一段：只有名字和描述。"""
    lines = ["可用的 Skill（需要时调 load_skill 读正文）："]
    lines += [f"- {s['name']}: {s['description']}" for s in skills]
    return "\n".join(lines)


def load_skill(skills: dict, name: str) -> str:
    return skills[name]["body"]


def read_skill_file(skills: dict, name: str, rel: str) -> str:
    p = (skills[name]["dir"] / rel).resolve()
    if skills[name]["dir"] not in p.parents:  # 不许读出 Skill 目录
        raise PermissionError(rel)
    return p.read_text(encoding="utf-8")


def run_skill_script(skills: dict, name: str, rel: str, stdin: str) -> str:
    script = skills[name]["dir"] / rel
    r = subprocess.run([sys.executable, str(script)], input=stdin, capture_output=True, text=True, timeout=30)
    return r.stdout.strip()


def main() -> None:
    found = [parse(d) for d in sorted(SKILLS.iterdir()) if (d / "SKILL.md").exists()]
    for s in found:
        print(f"[检查] {s['name']}: " + ("合规" if not s["problems"] else "；".join(s["problems"])))
    skills = {s["name"]: s for s in found}

    sys_prompt = level1(found)
    print(f"\n第 1 层 · 启动时进系统提示：{len(sys_prompt)} 字\n{sys_prompt}\n")

    print("你：order-svc 20:16 告警变慢，帮我查根因")
    print("模型：描述里有“告警”“变慢”“查根因”，调 load_skill('oncall-triage')")
    body = load_skill(skills, "oncall-triage")
    print(f"第 2 层 · 正文进上下文：{len(body)} 字（之前一直不在）")

    gc_log = oncall.GC_LOG
    summary = run_skill_script(skills, "oncall-triage", "scripts/gc_summary.py", gc_log)
    script_len = len((skills["oncall-triage"]["dir"] / "scripts/gc_summary.py").read_text(encoding="utf-8"))
    print(f"第 3 层 · 跑 scripts/gc_summary.py：脚本 {script_len} 字、GC 日志 {len(gc_log)} 字都没进上下文，"
          f"进去的只有输出 {len(summary)} 字：\n  {summary}")

    print("模型：Full GC 3 次、回收后 69～72%，越过阈值，读 references/gc-thresholds.md")
    ref = read_skill_file(skills, "oncall-triage", "references/gc-thresholds.md")
    print(f"第 3 层 · 按需读参考文件：{len(ref)} 字")

    total_disk = sum(len(p.read_text(encoding="utf-8")) for p in skills["oncall-triage"]["dir"].rglob("*") if p.is_file())
    used = len(sys_prompt) + len(body) + len(summary) + len(ref)
    print(f"\n这个 Skill 在磁盘上共 {total_disk} 字；这次排障进上下文 {used} 字；"
          f"没用到它的对话里，只占第 1 层的 {len(sys_prompt)} 字。")


if __name__ == "__main__":
    main()
