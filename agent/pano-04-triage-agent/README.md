# 全景：一次排障提问，agent 来回了几轮

对应页面：[全景：一次排障提问，agent 来回了几轮](https://study.ainiya.de/agent/pano-04-triage-agent.html)

值班助手 v1：不用任何框架，手写 agent 循环。

| 文件 | 是什么 |
|---|---|
| `oncall.py` | 5 个工具（4 个只读 + 要人点头的 `heap_dump`）、2026-11-08 20:16 那次 order-04 变慢的现场（假数据）、系统提示里的值班 SOP。站 04 后面的实验都 import 它 |
| `main.py` | agent 循环：调模型 → 分派工具 → 危险的先问人 → 结果包成 tool_result 送回 → 守门（步数、token、时长）→ 下一轮 |
| `fake_model.py` | 照剧本出牌的假模型，返回真 SDK 的 `Message` 对象。要“确定发生”的情况（停不下来、一轮三个工具）时用 |

## 怎么跑

没有 Claude 的 key 也能跑：llama.cpp 的 llama-server 实现了 `/v1/messages`（含 tools），SDK 的 `base_url` 指向本机。

```bash
# 起本机模型（llama.cpp b12618 源码编译，见 llm-next-token 的 README；模型 Qwen3-1.7B Q4_K_M，1.1 GB）
llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja -c 16384 --port 8090 -a qwen3-1.7b

cd agent && uv sync
uv run python pano-04-triage-agent/main.py              # heap_dump 默认被拒
uv run python pano-04-triage-agent/main.py --approve    # 同意 dump
# 有 key 时连 Claude：
ANTHROPIC_BASE_URL= ANTHROPIC_API_KEY=sk-ant-... MODEL=claude-sonnet-5-5 uv run python pano-04-triage-agent/main.py
```

整段对话写到 `transcript.json`（不进仓库）。

## 预期输出

2026-11-24，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M，anthropic 2.12.0，Python 3.13（模型带随机性，轮数和工具每次可能不同）：

```
第 1 轮： 85.4s  stop_reason=tool_use  输入 792 输出 453 token  要了 ['search_logs({"keyword": "慢请求", "since": "20:16"})', 'parse_gc_log({"since": "20:16"})']
   ← 7 字
   ← 12 字
第 2 轮： 41.0s  stop_reason=tool_use  输入 29 输出 247 token  要了 ['thread_dump({})']
第 3 轮： 54.5s  stop_reason=tool_use  输入 157 输出 274 token  要了 ['search_notes({"query": "导出Excel 线程阻塞"})']
第 4 轮： 62.2s  stop_reason=tool_use  输入 245 输出 269 token  要了 ['heap_dump({"reason": "导出Excel 导致老年代压力"})']
   ⚠ 模型要调用 heap_dump(...)，需要值班人员确认 → 拒绝
第 5 轮：106.1s  stop_reason=end_turn  输入 37 输出 464 token  要了 []
共 5 轮模型调用，4 批工具结果，2967 token，349.2s
```

“输入”是 llama-server 报的**没命中缓存**的部分（前缀留在 KV 里）。每轮完整提示用 `count_tokens` 重算是 792 → 1273 → 1676 → 2082 → 2387。

注意第 1 轮：模型把 `since` 填成告警时间 20:16，正好错过 20:09 开始的现场，两个工具都返回“没查到”。这是“工具怎么设计”那一页的素材。

## 练习

先猜结果，再运行。

1. 把 `parse_gc_log` 的 `since` 描述改成“告警前 15 分钟，例如告警 20:16 就填 20:00”，再跑，第 1 轮还会落空吗？
2. 把 `MAX_STEPS` 改成 2，循环停在哪，最后打印什么？
3. 加 `--approve` 再跑，模型拿到 dump 结果后结论有没有变？
