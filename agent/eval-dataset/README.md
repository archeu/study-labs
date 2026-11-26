# 评测集：从真实问题里收，扩到 50 条

对应页面：[评测集：从真实问题里收，扩到 50 条](https://study.ainiya.de/agent/eval-dataset.html)

站 09 的评测底座：50 条用例 + 运行器。后面几页（打分、tracing、轨迹、版本、CI、换模型）都 `import harness`。

| 文件 | 是什么 |
|---|---|
| `cases50.py` | 50 条用例：站 04 的 10 条 + 新收的 40 条，13 类故障，每条带现场（日志、GC、线程栈）、说中词、原因里不许出现的词、类型 `cat`、来源 `src`；可选 `tool_error`、`must_not_call` |
| `harness.py` | 运行器：值班助手（pano-04 的工具 + 站 04 的 v2 工具说明 + `prompts/` 下某版系统提示词），每条存对话、每次调用的耗时和 token（`spans`）、判分 |
| `run.py` | `stats` 覆盖和判据体检、`run` 跑一遍、`report` 按类型/来源/失败原因读、`regrade` 按存下的对话重判 |
| `prompts/` | 系统提示词各版本：`@3` 基线、`@4` PR #57（先查知识库）、`@5` 修好的那版 |
| `runs/` | 每次运行一个目录：每条一个 JSON + `scores.json` |

50 条都是仿照真实值班问题写的**假现场**，没有真实日志。

## 怎么跑

先起本机模型：llama.cpp b12618 的 `llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja -c 16384 --port 8090 -a qwen3-1.7b`（见 [pano-04-triage-agent](../pano-04-triage-agent/README.md)）。

```bash
cd agent && uv sync
uv run python eval-dataset/run.py stats
uv run python eval-dataset/run.py run --prompt @3 --name base-@3-r1      # 约 55 分钟
uv run python eval-dataset/run.py report base-@3-r1
uv run python eval-dataset/run.py run --prompt @3 --only 11 12 --name try
```

## 预期输出

2026-11-26，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M（关思考，服务端默认温度 0.8），anthropic 2.x，Python 3.13：

```
stats：13 类，每类 3～6 条；来源 值班记录 30、边角 10、差评回答 9、变体 1；判据体检清单为空（改之前 11 条有白送的词）
base-@3-r1：通过 2/50，看过现场 37，说中 8，怪错 30（27 条怪 GC），编造数字 4（全是改写被误判），违规调用 2
            平均 4.9 轮、63.6 秒、输出 291 token；整套约 55 分钟
```

没看现场的 13 条里，7 条是“错误率”告警：助手把“错误率”原样当关键字搜日志，查空后直接下结论。

## 练习

先猜结果，再运行。

1. 在 `tools_v2()` 的关键字说明里加“错误率告警用 ERROR|Exception”，`--only 11 12 16 22 28 37 38` 重跑，看“没看现场”降了多少。
2. 03 的说中词去掉 “SQL”“慢 SQL”，`regrade base-@3-r1`，看通过数变不变。
3. 照 `C(...)` 写一条你自己遇到过的故障，跑 `stats` 看体检。
