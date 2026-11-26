# 提示词和 Skill 的版本管理

对应页面：[提示词和 Skill 的版本管理](https://study.ainiya.de/agent/prompt-versioning.html)

提示词文件放在 [eval-dataset/prompts/](../eval-dataset/prompts/)（评测运行器从那里读），文件名就是版本：`oncall-system@3.md`。

| 文件 | 是什么 |
|---|---|
| `main.py` | `list` / `lock` / `check` / `diff` / `label` / `rollback` / `bundle` / `demo` / `traces` |
| `prompts.lock.json` | 每个已发布版本的内容指纹（sha256 前 12 位）。已登记的版本内容一变，`check` 就失败 |
| `labels.json` | 标签 → 版本的指针（`production`、`canary`），每次移动都记历史 |
| `skills/oncall-triage/SKILL.md` | 值班 SOP 写成的 Skill，算进发布包指纹 |

## 怎么跑

```bash
cd agent
uv run python prompt-versioning/main.py list
uv run python prompt-versioning/main.py check          # CI 第一步
uv run python prompt-versioning/main.py diff @3 @4
uv run python prompt-versioning/main.py bundle         # 工具说明 v1 和 v2 的发布包指纹
uv run python prompt-versioning/main.py demo           # 临时目录里演示：原地改 @3 被抓住、另起 @6、标签上线和回滚
uv run python prompt-versioning/main.py traces ../agent-tracing/traces/base-@3-r1.jsonl
```

## 预期输出

2026-11-26，Python 3.13：

```
oncall-system@3 a14cfb8ab302 / @4 951128fb010d / @5 eacc72edb9d9
bundle：同样是 @3，工具说明 v1 → 55f946871efa，v2 → c7a96dce9f2a
demo：原地改 @3 → check 报“已发布的版本被原地改了（登记 a14cfb8ab302，现在 48b7dfed773f）”
traces：@3 没过 48、通过 2；@4 没过 47、通过 3
```

## 练习

1. 把 `bundle()` 的总指纹写进 eval-dataset 的 `scores.json`。
2. 写一个只跑 `check` 的 GitHub Actions job。
3. 在 tracing 的 `run_traced` 里给根 span 加 `gen_ai.skill.name` 和带提交号的 `gen_ai.skill.source.uri`。
