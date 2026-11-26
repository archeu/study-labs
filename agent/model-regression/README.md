# 换模型、升级模型怎么回归

对应页面：[换模型、升级模型怎么回归](https://study.ainiya.de/agent/model-regression.html)

换模型不写新代码：评测集、判分、门禁、轨迹评测全部原样用（[eval-dataset](../eval-dataset/README.md)），只换 `MODEL` 和本机加载的模型文件。这里的 `main.py` 只负责把四组运行（模型 × 提示词）摆成总表，并对任意两组做逐条配对。

| 运行 | 模型 | 提示词 |
|---|---|---|
| `base-@3-r1` | Qwen3-1.7B Q4_K_M | @3（线上基线） |
| `base-@3-qwen06-r1` | Qwen3-0.6B Q8_0 | @3（直接换） |
| `tuned-@6-qwen17-r1` | Qwen3-1.7B | @6（第 1 步写死成先搜日志） |
| `tuned-@6-qwen06-r1` | Qwen3-0.6B | @6 |

## 怎么跑

```bash
llama-server -m Qwen3-0.6B-Q8_0.gguf --jinja -c 16384 --port 8090 -a qwen3-0.6b --cache-ram 0
cd agent
MODEL=qwen3-0.6b uv run python eval-dataset/run.py run --prompt @3 --name base-@3-qwen06-r1
MODEL=qwen3-0.6b uv run python eval-dataset/run.py run --prompt @6 --name tuned-@6-qwen06-r1
uv run python eval-trajectory/main.py judge tuned-@6-qwen06-r1
uv run python model-regression/main.py table
uv run python model-regression/main.py pair tuned-@6-qwen17-r1 tuned-@6-qwen06-r1
```

## 预期输出

2026-11-27，2 核 ARM，llama.cpp b12618（`--cache-ram 0`），关思考，温度 0.8：

```
组                      通过 说中 怪错 看过现场 关键证据 轮数  耗时  输出token
1.7B + @3（线上基线）       2    8   30       37       22   4.9  63.6s    291
0.6B + @3（直接换）         1    1   27        3        3   2.7  17.1s    136
1.7B + @6（只换提示词）      6   17   29       46       35   5.6  78.3s    360
0.6B + @6                   7    8   15       30       17   3.7  22.0s    170

同一提示词只换模型（1.7B@6 → 0.6B@6）：说中 显著变坏（b14 c5），关键证据 显著变坏（b21 c3），
没怪错 显著变好（b5 c19），耗时 ×0.28
```

结论：0.6B 直接换不行；“0.6B + @6 比线上好”是比错了基线，@6 给 1.7B 用同样明显提升；同一提示词下 0.6B 查得少、说中少，按“降本提速、质量不许显著变坏”的规则不能换。

## 练习

1. 用 `gate.py base-@3-r1 base-@3-qwen06-r1` 看换模型时门禁拦在哪一条。
2. 1.7B 开思考（`--thinking`）跑 10 条冒烟组，当成一次“升级”，按“提质量”的规则判。
3. 误报、下游依赖两类交给 0.6B，其余给 1.7B，拼成一个新版本和全用 1.7B 比。
