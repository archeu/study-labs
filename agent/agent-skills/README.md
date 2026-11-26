# Agent Skills：按需加载的说明书

对应页面：[Agent Skills：按需加载的说明书](https://study.ainiya.de/agent/agent-skills.html)

| 文件 | 是什么 |
|---|---|
| `skills/oncall-triage/` | 值班排障 SOP 写成的 Skill：`SKILL.md`（name + description + 五步流程）、`scripts/gc_summary.py`（GC 日志 → 一行摘要）、`references/gc-thresholds.md`（阈值表） |
| `skill_host.py` | 迷你 Skill 主机（不调模型）：按规范检查 frontmatter，演示三层按需加载，打印每层进上下文多少字 |

## 怎么跑

```bash
cd agent && uv sync
uv run python agent-skills/skill_host.py
uvx --from skills-ref agentskills validate agent-skills/skills/oncall-triage    # 官方校验工具
uvx --from skills-ref agentskills to-prompt agent-skills/skills/oncall-triage   # 第 1 层的标准写法
# 装进 Claude Code：把 skills/oncall-triage 复制到仓库的 .claude/skills/ 或 ~/.claude/skills/，然后 /oncall-triage
```

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，skills-ref 0.1.1：

```
[检查] oncall-triage: 合规
第 1 层 · 启动时进系统提示：182 字
第 2 层 · 正文进上下文：731 字（之前一直不在）
第 3 层 · 跑 scripts/gc_summary.py：脚本 603 字、GC 日志 835 字都没进上下文，进去的只有输出 50 字：
  停顿 7 次，最长 1966 ms；Full GC 3 次，回收后堆占用 69%, 72%, 72%
第 3 层 · 按需读参考文件：438 字
这个 Skill 在磁盘上共 1947 字；这次排障进上下文 1401 字；没用到它的对话里，只占第 1 层的 182 字。

$ agentskills validate agent-skills/skills/oncall-triage
Valid skill: agent-skills/skills/oncall-triage
```

## 练习

1. 加 `references/thread-states.md`，正文第 4 步指向它；看第 2 层字数变不变。
2. 写 `scripts/log_window.py`：输入告警时间，输出往前推 10 分钟的起点。
3. 写第二个 Skill `postmortem-report`，描述里写清和 `oncall-triage` 的分工。
