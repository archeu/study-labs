# 省钱：提示词缓存和批处理

对应页面：[省钱：提示词缓存和批处理](https://study.ainiya.de/agent/api-prompt-cache-batch.html)

站 03 地图过关题 2：实测提示词缓存命中前后的成本和延迟。没有 Claude 的 key，延迟用本机 llama-server 实测（它会自动复用相同前缀的 KV，`usage` 里有 `cache_read_input_tokens`），成本按 Claude 单价换算。

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 8090 端口的服务。

```bash
cd agent && uv sync
uv run python api-prompt-cache-batch/main.py warm    # 固定前缀 + 10 条告警（第 1 次约 45 秒）
uv run python api-prompt-cache-batch/main.py break   # 前缀开头放请求号，3 次都冷（约 2.5 分钟）
```

注意：服务端会把前缀留在缓存里，`warm` 第二次跑时第 1 次也会命中；想重现“冷启动”，重启 llama-server。`break` 用时间戳生成请求号，每次都不同。

## 预期输出

2026-11-22，anthropic 2.12.0 → llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M：

```
第  1 次：新算 1919，缓存读    0，首字 43.35s
第  2 次：新算   15，缓存读 1899，首字  0.63s
...
按 Claude Opus 5.5 价格：不开缓存 $0.0820，开缓存 $0.0189（省 77%）

（break）每次新算约 1940，缓存读 0，首字约 44s
按 Claude Opus 5.5 价格：不开缓存 $0.0253，开缓存 $0.0311（反而多花 23%）
```

批处理（Message Batches）本机无法运行，写法见页面 ④。

## 练习

1. 把请求号从系统提示开头挪到用户消息最后，再跑 `break`。
2. 在手册中间改一个字，第 2 次请求能命中多少？
3. 用自己估的调用量、前缀长度和请求间隔，在页面的实验台里算一个月的费用。
