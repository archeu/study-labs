# 记忆好不好：该记的记住了吗，对任务有帮助吗

对应页面：[记忆好不好：该记的记住了吗，对任务有帮助吗](https://study.ainiya.de/agent/memory-eval.html)

## 怎么跑

```bash
# llama.cpp b12618，模型 Qwen/Qwen3-Embedding-0.6B-GGUF 的 Q8_0
llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
cd agent && uv sync
EMBED_URL=http://127.0.0.1:8091/v1/embeddings uv run python memory-eval/main.py   # 本机 15 秒
```

- `cases.py`：20 个用例（记住 7、忘掉 5、冲突 6、隐私 2）+ 背景记忆 + 补充的第 21 个（专门抓“去掉新近项”）。每个用例 = 事件序列 + 提问时刻 + 问题 + must / must_not / once。
- `system.py`：被测的记忆系统，8 个开关（typed、upsert、revoke、source、recency、team、expire、redact）；全关 = v1，全开 = v2。`check` 是判定。
- `main.py`：跑 v1、v2 和两次“改坏了”；补上第 21 个再跑；256 种开关组合写进 `grid.json`（页面实验台查的就是它）。

需要的第三方包用 `uv add <包>` 加到 `agent/pyproject.toml`。

## 预期输出

2026-11-24，2 核 ARM，Python 3.13，Qwen3-Embedding-0.6B Q8_0：

```
v1：全追加 + 相似度前 3        通过 8/20  记住 7/7  忘掉 0/5  冲突 1/6  隐私 0/2
v2：本站规则全开              通过 20/20
v2 改坏①：去掉新近项           通过 20/20   ← 没发现
v2 改坏②：新接口漏了团队条件     通过 19/20
补上第 21 个后：v1 8/21，v2 21/21，改坏① 20/21，改坏② 20/21
```

只关一个开关时挂掉的用例：upsert #13 #15 #18；revoke #11 #14 #16；source #10；recency #21；team #9；expire #8；redact #19 #20；typed 无（盲区）。

## 练习

先猜结果，再运行。

1. 把 v1 挂掉的 12 个用例逐个对回前四页的规则。
2. 补一个用例，让关掉 typed 时会挂（交易组放 12 条以上记忆）。
3. 挑 5 个用例做任务层对比：带记忆 / 不带记忆，人工判答案对不对。
4. 拿 memory-write 的会话记录列出应抽条目，量抽取召回率。
5. 写脚本：8 个开关各关一次，打印没有用例挂的开关。
