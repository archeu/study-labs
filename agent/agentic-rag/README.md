# 让 agent 自己决定查什么（Agentic RAG）

对应页面：[让 agent 自己决定查什么（Agentic RAG）](https://study.ainiya.de/agent/agentic-rag.html)

## 怎么跑

要 8091（Embedding）和 8093（Qwen3-1.7B，`--jinja`），见 [pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md)。生成走 llama-server 的 `/v1/messages`，用 Anthropic SDK 调。

```bash
cd agent && uv sync
uv run python -W ignore agentic-rag/main.py                  # A B C D 四种做法 × 4 题，约 12 分钟
uv run python -W ignore agentic-rag/main.py E --only=A1,A2    # 打开思考（每题约 2 分钟）
uv run python -W ignore agentic-rag/main.py F                 # 人写检索计划
```

- A 原话查一次；B 模型先列计划再查；C agent 循环（search_kb 工具，最多 4 次，查满收走工具）；D 第一轮 tool_choice 逼它查；E 再打开思考；F 人按题型写好检索计划。
- 4 道题：A1～A3 要拼两三处资料，A4 是单跳对照。`runs.json` 存每题的检索语句、拿到的片、回答原文；对错是人判的（见页面 ⑤）。

## 预期输出

2026-11-25，2 核 ARM，Qwen3-1.7B Q4_K_M，llama.cpp b12618：

```
A 原话查一次    1 / 4 对（只有单跳 A4）
B 先列计划再查  1 / 4
C agent 循环    0 / 4：A1、A3、A4 一次都没查就回答，A1 编了 [S3]
D 第一轮必须查  1 / 4：本机服务端没执行 tool_choice，A1 照样没查
E 必须查 + 思考 0 / 2：会查了，但只查一次、查得不准，每题约 2 分钟
F 人写检索计划  2 / 4 对，A3 部分对
```

## 练习

先猜结果，再运行。

1. `run_c` 里一次没查就回答时，丢掉回答、追加“请先调用 search_kb”再要一次。
2. 回答里的 [S数字] 必须是工具返回过的，否则判错。
3. 让 F 组的 A1 拿到时间线（改检索语句或每条取前 3 片）。
4. 模型只输出“规定 / 事件 / 两者都要”，代码按类别写检索语句。
