# 危险操作先问人

对应页面：[危险操作先问人](https://study.ainiya.de/agent/human-approval.html)

工具按风险分级（只读 allow、有影响 ask、不可逆 deny，没登记的也 deny），问人时打印审批卡片、有超时、超时按拒绝；拒绝理由作为 `is_error` 回执送回模型；每个决定写一行审计日志 `audit.jsonl`（不进仓库）。模型和“值班的人”都用剧本，方便复现。

## 怎么跑

```bash
cd agent && uv sync
uv run python human-approval/main.py                # 四个剧本：同意、拒绝并附理由、没人回复、换工具名绕过
uv run python human-approval/main.py --interactive  # 你来审批：y 或 n 理由，10 秒不答按拒绝
```

## 预期输出

2026-11-24，2 核 ARM，Python 3.13：

```
=== A 同意 ===           第 2 轮 heap_dump       → approved（1.2s）
=== B 拒绝并附理由 ===    第 2 轮 heap_dump       → rejected（0.8s）  回执：值班人员拒绝：晚高峰别 dump，先看 jmap -histo 的结果……
=== C 没人回复 ===        第 2 轮 heap_dump       → timeout （3.0s）  回执：3 秒内没有人确认，按拒绝处理……
=== D 换个工具名绕过 ===  第 2 轮 restart_service → denied；第 3 轮 run_shell → denied（没有登记风险等级）
审计日志：human-approval/audit.jsonl（13 条）
```

只读的 8 次调用都自动放行，只有 3 次 heap_dump 弹了卡片。

## 练习

先猜结果，再运行。

1. 审批时记下 `tool_use_id` 和参数哈希，执行前核对；让模型下一轮换参数再调，确认不会执行。
2. 同一次排查里被拒过的同名同参数调用，直接回上次的理由，不再问。
3. 把这道闸门换进全景页 `main.py` 的 `ask_human`，用 `--interactive` 跑一次真模型。
