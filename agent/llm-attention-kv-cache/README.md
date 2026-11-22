# 注意力和 KV 缓存（只讲到够用）

对应页面：[注意力和 KV 缓存（只讲到够用）](https://study.ainiya.de/agent/llm-attention-kv-cache.html)

连本机 llama-server，只用标准库。`cache_prompt` 控制服务端是否复用同一个槽里相同前缀的 KV。

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 llama-server（8090 端口）。

```bash
cd agent
uv run python llm-attention-kv-cache/main.py prefix    # 同一日志换问题 / 时间戳放开头 / 放后面（约 30 秒）
uv run python llm-attention-kv-cache/main.py nocache   # 写 20 个 token：有 KV 缓存 vs 每圈全部重算（约 2 分钟）
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M，Python 3.13：

```
有 KV 缓存（只算新 token）：写 20 个 token 用    0.8 s，一共算了    20 个 token 位置
没有 KV 缓存（每圈全部重算）：写 20 个 token 用  127.6 s，一共算了  6130 个 token 位置

A. 同一段日志，换一个问题：第 1 次新算 287 个 6092 ms；第 2 次新算 9 个，复用 273 个，219 ms
B. 当前时间放在系统提示开头：每次新算 293 个，复用 0 个，约 6 秒
C. 同样的时间放在问题后面：新算 9～31 个，复用 263～285 个
```

## 练习

先猜结果，再运行。

1. 加一组 D：把日志第 2 行的 `1873.412ms` 改成 `1873.413ms`，新算多少个？
2. 配置字典 `json.dumps` 后放进系统提示，`sort_keys=True` 和打乱顺序各跑一次，比较 `cache_n`。
3. 把 `nocache` 的写出长度改成 40，先预测两种做法的总耗时。
