"""危险操作先问人：工具按风险分级，按策略自动放行 / 问人 / 直接拒绝；问人有超时，超时按拒绝；每次决定写审计日志。

    python main.py                 # 四个剧本：同意、拒绝并附理由、超时、模型想绕过（换个工具名）
    python main.py --interactive   # 审批由你在终端里输入 y / n / 理由（10 秒不答按拒绝）

模型用照剧本出牌的假模型；“值班的人”在非交互模式下也是剧本，方便复现。
"""

import json
import select
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall                      # noqa: E402
from fake_model import FakeClient  # noqa: E402

# ---- 策略：按工具的风险等级，不按模型说的理由 ----
RISK = {"search_logs": "read", "parse_gc_log": "read", "thread_dump": "read", "search_notes": "read",
        "heap_dump": "impact", "restart_service": "irreversible"}
POLICY = {"read": "allow", "impact": "ask", "irreversible": "deny"}
APPROVAL_TIMEOUT = 3.0          # 演示用；线上按值班响应时间定，比如 5 分钟
AUDIT = Path(__file__).with_name("audit.jsonl")


@dataclass
class Decision:
    verdict: str                # allow / approved / rejected / timeout / denied
    note: str = ""
    waited: float = 0.0


def ask_human(name, args, evidence, answer_script):
    """把审批卡片发给值班的人，等回答。answer_script 是剧本：('y'|'n'|None, 理由, 几秒后回)。"""
    card = (f"【需要确认】order-svc 排查助手想执行 {name}({json.dumps(args, ensure_ascii=False)})\n"
            f"  影响：jmap -dump:live 会触发 Full GC，order-svc 停顿约 4 秒（晚高峰）\n"
            f"  已有证据：{evidence}\n"
            f"  {APPROVAL_TIMEOUT:.0f} 秒内不回复按拒绝处理")
    print("   " + card.replace("\n", "\n   "))
    t0 = time.perf_counter()
    if answer_script is None:                                   # 交互模式
        print("   你的决定 [y / n 理由]：", end="", flush=True)
        ready, _, _ = select.select([sys.stdin], [], [], APPROVAL_TIMEOUT)
        if not ready:
            print()
            return Decision("timeout", waited=time.perf_counter() - t0)
        line = sys.stdin.readline().strip()
        yes = line.lower().startswith("y")
        return Decision("approved" if yes else "rejected", line[1:].strip(), time.perf_counter() - t0)
    ans, note, delay = answer_script
    if ans is None or delay >= APPROVAL_TIMEOUT:
        time.sleep(APPROVAL_TIMEOUT)
        return Decision("timeout", waited=time.perf_counter() - t0)
    time.sleep(delay)
    return Decision("approved" if ans == "y" else "rejected", note, time.perf_counter() - t0)


def gate(name, args, evidence, answer_script) -> Decision:
    level = RISK.get(name)
    if level is None:
        return Decision("denied", f"没有登记风险等级的工具 {name}，一律拒绝")
    policy = POLICY[level]
    if policy == "allow":
        return Decision("allow")
    if policy == "deny":
        return Decision("denied", f"{name} 属于不可逆操作，助手无权执行，请值班人员手动处理")
    return ask_human(name, args, evidence, answer_script)


def to_result(block, d: Decision) -> dict:
    if d.verdict in ("allow", "approved"):
        return {"type": "tool_result", "tool_use_id": block.id, "content": oncall.IMPL[block.name](**block.input)}
    msg = {"rejected": f"值班人员拒绝：{d.note or '没有说明理由'}。请用已有证据给结论，并说明还缺什么。",
           "timeout": f"{APPROVAL_TIMEOUT:.0f} 秒内没有人确认，按拒绝处理。请用已有证据给结论。",
           "denied": d.note}[d.verdict]
    return {"type": "tool_result", "tool_use_id": block.id, "is_error": True, "content": msg}


def run(title, script, human):
    print(f"\n=== {title} ===")
    client = FakeClient(script)
    messages, evidence = [{"role": "user", "content": oncall.QUESTION}], []
    for step in range(1, 6):
        r = client.messages.create(model="fake", tools=oncall.TOOLS, messages=messages)
        messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in r.content]})
        if r.stop_reason == "end_turn":
            print(f" 第 {step} 轮 模型交答案")
            break
        results = []
        for b in (b for b in r.content if b.type == "tool_use"):
            d = gate(b.name, b.input, "；".join(evidence[-2:]) or "暂无", human)
            res = to_result(b, d)
            results.append(res)
            if not res.get("is_error"):
                evidence.append(res["content"][:40])
            with AUDIT.open("a") as f:                         # 审计：谁、什么、怎么决定、等了多久
                f.write(json.dumps({"ts": time.strftime("%H:%M:%S"), "tool": b.name, "args": b.input, "risk": RISK.get(b.name),
                                    "verdict": d.verdict, "note": d.note, "waited_s": round(d.waited, 2)}, ensure_ascii=False) + "\n")
            print(f" 第 {step} 轮 {b.name:15} → {d.verdict:8}" + (f"（{d.waited:.1f}s）" if d.waited else "") +
                  (f"  回执：{res['content'][:52]}" if res.get("is_error") else ""))
        messages.append({"role": "user", "content": results})


def main():
    AUDIT.unlink(missing_ok=True)
    first = [("search_logs", {"keyword": "耗时", "since": "20:00"}), ("parse_gc_log", {"since": "20:00"})]
    dump = [("heap_dump", {"reason": "确认老年代里是什么对象"})]
    answer = "结论：……"
    if "--interactive" in sys.argv:
        run("交互：你来审批", [first, dump, answer], None)
    else:
        run("A 同意", [first, dump, answer], ("y", "", 1.2))
        run("B 拒绝并附理由", [first, dump, answer], ("n", "晚高峰别 dump，先看 jmap -histo 的结果", 0.8))
        run("C 没人回复", [first, dump, answer], (None, "", 0))
        run("D 换个工具名绕过", [first, [("restart_service", {"name": "order-svc"})], [("run_shell", {"cmd": "jmap -dump:live"})], answer], ("y", "", 0.5))
    print(f"\n审计日志：{AUDIT}（{sum(1 for _ in AUDIT.open())} 条）")


if __name__ == "__main__":
    main()
