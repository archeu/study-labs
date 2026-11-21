# 一次只写一个 token：下一个词预测

对应页面：[一次只写一个 token：下一个词预测](https://study.ainiya.de/agent/llm-next-token.html)

不用 SDK，让本机模型服务每次只算 1 个 token，自己写“前文 → 概率表 → 挑一个 → 接上”的循环。只用标准库。

## 准备本机模型（没有 API key 也能跑）

```bash
# llama.cpp：发布包要 GLIBC 2.38，Ubuntu 22.04 上自己编（2 核约 7 分钟）
git clone --depth 1 --branch b12618 https://github.com/ggml-org/llama.cpp
cd llama.cpp && cmake -B build -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build -j2 --target llama-server llama-tokenize
# 模型：Qwen2.5-0.5B-Instruct，4 位量化，483 MB
curl -LO https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf
build/bin/llama-server -m qwen2.5-0.5b-instruct-q4_k_m.gguf --port 8090 -c 8192 -t 2 --no-webui
```

服务地址不是 `http://127.0.0.1:8090` 时，设环境变量 `LLAMA_URL`。

## 怎么跑

```bash
cd agent
uv run python llm-next-token/main.py                 # 手写循环，逐 token 打印前 3 名候选
uv run python llm-next-token/main.py --once          # 服务端一口气写完，对比结果
uv run python llm-next-token/main.py --force 不是    # 替模型写好开头
uv run python llm-next-token/main.py --ignore-end    # 挑中结束符也不停
uv run python llm-next-token/main.py --dump steps.json   # 每一圈的候选存成 JSON
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618，Python 3.13，温度 0：

```
输入一共 297 个 token（含对话模板的特殊 token）
  1 '是'          p=0.707   其次 '不是' 0.205  '否' 0.018
  2 '，'          p=0.268   其次 '' 0.210  '。' 0.201      ('' 是结束符)
  ...
 24 '慢'          p=0.153   其次 '多' 0.148  '快' 0.090
  ...
 62 '<|im_end|>'  p=0.925
   → 挑中结束符 <|im_end|>，自己停下（stop_reason = end_turn）
回答：是，GC导致的。依据：20:15的GC时间比20:14的慢，20:14的GC时间是198.204ms，20:15的GC时间是198.204ms。
```

- `--once`：62 个 token，和手写循环一字不差；读输入约 48 个/秒，写输出 24～25 个/秒。
- `--force 不是`：写出“不是，是 GC 导致的……”，然后重复“时间差”直到 120 个 token 截断。
- `--ignore-end`：结束符之后接着写 `<|endoftext|>Human: [2026-11-08T20:12:03…`。
- 服务器里先跑过别的问题、缓存里留着相同前缀时，第 24 圈可能翻成“多”，整句变短：浮点运算顺序不同，概率差 0.005 的两个候选会翻转。

## 练习

先猜结果，再运行。

1. 把 `QUESTION` 改成“先逐字引用最相关的一行日志，再用一句话判断是不是 GC 导致的”。第一个 token 变成什么？引用的那一行在不在日志里？
2. 把 `MAX_NEW` 改成 5，跑 `--once`，停因从 `eos` 变成什么？
3. `--force "从日志看"`、`--force "无法判断"` 各跑一次，比较三种开头带出来的整句。
