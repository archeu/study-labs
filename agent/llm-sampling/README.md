# 采样：temperature 和 top_p 改的是什么

对应页面：[采样：temperature 和 top_p 改的是什么](https://study.ainiya.de/agent/llm-sampling.html)

连本机 llama-server（4 个并发槽），只用标准库。为了只看温度一个变量，请求里把 llama.cpp 默认开着的 top_k / top_p / min_p 都关掉了。

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 llama-server（8090 端口，默认 4 个槽）。

```bash
cd agent
uv run python llm-sampling/main.py probs   # 第一个 token 的前 20 名概率
uv run python llm-sampling/main.py runs    # 温度 0 和 1 各跑 10 次（约 30 秒）
uv run python llm-sampling/main.py batch   # 温度 0：一个一个发 vs 4 个同时发（约 45 秒）
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M，Python 3.13：

```
温度 0.0：10 次里有 1 种不同的回答；开头字分布 {'是': 10}
温度 1.0：10 次里有 7 种不同的回答；开头字分布 {'是': 6, '不': 1, '不是': 2, 'Y': 1}
   ×1 不是，是 GC 渲染导致的。   ...

一个一个发（温度 0）：两次都是 “……比20:14的慢，20:14的GC时间是198.204ms……”
4 个同时发（温度 0）：3 个变成 “……比20:14的多，且20:14的GC时间比20:13的多。”
```

并发那一组是否翻转、翻几个，取决于服务端怎么拼批，换机器可能不同；只要有一次不同，就说明温度 0 不保证逐字一样。

## 练习

先猜结果，再运行。

1. `gen()` 里把 `top_p` 改成 0.9，温度 1 再跑 10 次：怪回答还会出现吗？
2. 温度 1 跑 10 次，用正则判断“是 / 否 / 其他”，取多数。
3. 用 llm-next-token 的手写循环，在 4 个线程同时跑时逐圈打印前两名，找出概率差最小的那一圈。
