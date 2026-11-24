# 先写 10 条评测用例，后面每站都跑

对应页面：[先写 10 条评测用例，后面每站都跑](https://study.ainiya.de/agent/eval-first-cases.html)

| 文件 | 是什么 |
|---|---|
| `cases.py` | 10 条用例：问题 + 现场（替换日志 / GC / 线程栈，或弄坏一个工具）+ 标准答案（`expect_any`、`must_not`） |
| `main.py` | 布置现场 → 跑值班助手 v1（全景页那套）→ 代码判分（看过现场、说中、怪错、编造数字、空回答）→ 存对话和分数 |
| `runs/2026-11-24/` | v1 的第一份分数和 10 条完整对话 |
| `runs/2026-11-24-v2/` | 只改工具说明（`--v2`）后的分数和对话 |

## 怎么跑

先起本机模型（见 [pano-04-triage-agent](../pano-04-triage-agent/README.md)），或者连 Claude。

```bash
cd agent && uv sync
uv run python eval-first-cases/main.py                     # v1，10 条（本机关思考约 7 分钟）
uv run python eval-first-cases/main.py --v2                # 只改工具说明再跑
uv run python eval-first-cases/main.py --only 02 05        # 只跑某几条
uv run python eval-first-cases/main.py --regrade 2026-11-24   # 判分规则改了：按存下的对话重判，不重跑模型
uv run python eval-first-cases/main.py --thinking          # 开思考（本机慢很多）
```

本机模型默认关思考（`chat_template_kwargs.enable_thinking=false`）：开着每轮 50～80 秒，关掉 4～10 秒。连 Claude 时不传这个参数。

## 预期输出

2026-11-24，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M（关思考），anthropic 2.12.0，Python 3.13：

```
v1：{"通过": 0, "共": 10, "看过现场": 0, "说中": 3, "怪错": 6, "编造数字": 0, "平均轮数": 3.1, "落空率": 0.5, "总秒": 388}
v2：{"通过": 2, "共": 10, "看过现场": 10, "说中": 6, "怪错": 5, "编造数字": 3, "平均轮数": 5.4, "落空率": 0.0, "总秒": 913}
```

v1 每条都是第一轮用告警里的“P99”、从 20:16 查日志，查空后直接去知识库猜。v2 只改了工具说明，模型开始看现场，但仍爱怪 GC，并开始编数字。

这组用例第一次跑时还抓到了：判分器的三处错（告警里的数字当编造、关键词太宽、“原因”一段截短了），以及 `parse_gc_log` 把“每次约 1.9s”“有 Humongous Allocation”写死的 bug（已修，全景现场数据的返回不变）。

## 练习

先猜结果，再运行。

1. 挑一条失败的，只改一处，`--only` 重跑它，再跑全部 10 条，确认没有修好一条、弄坏另一条。
2. `--only 01` 连跑 5 次，算通过率。
3. 把 `requirements-to-design/sop/` 里的判断规则和分支写进 `oncall.SYSTEM`，加 `--v2` 再跑，看怪 GC 的有没有变少。
