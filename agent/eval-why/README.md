# 为什么必须评测：输出不确定，改一处坏一片

对应页面：[为什么必须评测：输出不确定，改一处坏一片](https://study.ainiya.de/agent/eval-why.html)

被测系统、用例、判分全部复用 [eval-first-cases](../eval-first-cases/README.md)（站 04 的 10 条 + 值班助手 v2），这里一行不改，只是多跑几遍、逐条比。

| 文件 | 是什么 |
|---|---|
| `main.py` | `same`：同一条用例原样跑 N 遍；`repeat`：10 条原样再跑一遍；`diff`：两份结果逐条对照，每个指标列出谁变好、谁变坏 |
| `runs/2026-11-26/same-02-pool-exhausted/` | 用例 02 跑 5 遍的对话和分数 |
| `runs/2026-11-26/repeat/` | 10 条 v2 原样重跑的对话和分数 |

## 怎么跑

先起本机模型（见 [pano-04-triage-agent](../pano-04-triage-agent/README.md)：llama.cpp b12618 的 llama-server，Qwen3-1.7B Q4_K_M，端口 8090）。

```bash
cd agent && uv sync
uv run python eval-why/main.py same 02 --times 5        # 约 7 分钟
uv run python eval-why/main.py repeat                   # 约 13 分钟
uv run python eval-why/main.py diff eval-first-cases/runs/2026-11-24-v2 eval-why/runs/2026-11-26/repeat   # 噪声
uv run python eval-why/main.py diff eval-first-cases/runs/2026-11-24 eval-first-cases/runs/2026-11-24-v2  # 改动
```

## 预期输出

2026-11-26，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M（关思考，服务端默认温度 0.8），anthropic 2.x，Python 3.13：

```
same 02 ×5：通过 1 遍（第 1 遍），另外 4 遍都把原因怪到 GC 上；工具调用顺序 5 遍几乎一样
repeat 对 11-24-v2：通过翻面 3 条（02、08 变坏，10 变好），说中翻面 4 条，看过现场 0 条
v1 → v2：通过 c=2 b=0；看过现场 c=10；说中 c=4 b=1；没编造数字 b=3
```

结论：v1 → v2 只有“看过现场”超出噪声（10 > 2√10 ≈ 6.3）；“通过 +2”比同一版两遍的翻面（3 条）还少，分不出；“开始编数字”贴着噪声线，10 条判不了。

## 练习

先猜结果，再运行。

1. `same 02 --times 10`，再挑一条你觉得最稳的（比如 08）也跑 10 遍，算两条的 p 和 `2p(1−p)`。
2. 把 `oncall.SYSTEM` 里“只用工具拿到的事实，不要猜数字”删掉，跑 `repeat`，用 `diff` 和今天的 repeat 比，看“没编造数字”的 b 有没有超出 `2√(b+c)`。
3. 给 `cmd_diff` 加上故障类型，按类型汇总翻面，看 GC 类和非 GC 类翻的方向是不是一样。
