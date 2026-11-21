# 上下文窗口：输入输出共用，越长越慢越贵

对应页面：[上下文窗口：输入输出共用，越长越慢越贵](https://study.ainiya.de/agent/llm-context-window.html)

三个实验，连本机 llama-server（窗口 8192），只用标准库。日志按固定随机种子生成，每次一样。

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 llama-server（`-c 8192`，8090 端口）。

```bash
cd agent
uv run python llm-context-window/main.py overflow   # 超过窗口：服务端直接 400
uv run python llm-context-window/main.py speed      # 输入 500～4200 token：读输入耗时、写输出速度（约 3 分钟）
uv run python llm-context-window/main.py needle     # Full GC 那一行埋在开头 / 中间 / 末尾（约 3 分钟）
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M，Python 3.13：

```
输入 9716 个 token，窗口 8192：
   {"code": 400, ..., "type": "exceed_context_size_error", "n_prompt_tokens": 9716, "n_ctx": 8192}

  输入 token   读输入 ms   读输入 个/秒   写输出 个/秒
       519       10923         47.5          24.6
      1083       24068         45.0          21.1
      2134       49126         43.4          16.5
      4232      110385         38.3          11.2

埋在开头（第 2 行 / 共 39 行）：没找到   回答：194.897ms
埋在中间（第 20 行 / 共 39 行）：没找到   回答：196.218ms
埋在末尾（第 38 行 / 共 39 行）：找到     回答：1873.412ms
```

## 练习

先猜结果，再运行。

1. 先用正则挑出停顿超过 1000ms 的行，放进 `<重点>…</重点>` 排在日志前面，三个位置再跑一次。
2. 把问题挪到日志之前，再跑埋针，“末尾找到”还成立吗？
3. 构造 8100 个 token 的输入、`n_predict` 设 500：服务端是拒绝还是写到一半停下？
