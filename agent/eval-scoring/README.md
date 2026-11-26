# 怎么打分：规则断言、参考答案、LLM 当评委

对应页面：[怎么打分：规则断言、参考答案、LLM 当评委](https://study.ainiya.de/agent/eval-scoring.html)

被判的回答来自 [eval-dataset](../eval-dataset/README.md) 的基线运行 `base-@3-r1`。

| 文件 | 是什么 |
|---|---|
| `refs.py` | 50 条用例的参考答案，每条一句“真实原因” |
| `labels.py` | 人工标注：50 条回答的原因对不对（1 / 0.5 / 0），标注人和口径写在文件开头 |
| `main.py` | `judge`：LLM 评委判（可 `--no-ref` 不给参考答案、`--tag` 区分多次）；`agree`：规则、评委、人工逐条对照；`dump`：打出每条的“原因”段供人工标注 |
| `runs/` | 评委三次的结果：带参考 50 条、原样再判 19 条、不给参考 19 条 |

## 怎么跑

先起本机模型（Qwen3-1.7B，见 eval-dataset 的 README）。评委默认用同一个模型，这是故意的。

```bash
cd agent
uv run python eval-scoring/main.py judge base-@3-r1                          # 约 15 分钟
uv run python eval-scoring/main.py judge base-@3-r1 --tag again --only 0 1
uv run python eval-scoring/main.py judge base-@3-r1 --no-ref --tag noref --only 0 1
uv run python eval-scoring/main.py agree base-@3-r1
```

## 预期输出

2026-11-26，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M 当评委（关思考，温度 0.8）：

```
评委（带参考）：对 6、部分对 17、错 27；每条约 17 秒
规则 对 人工：一致 45/50，假通过 2（03、25），假失败 3（20、27、44）
评委 对 人工：一致 45/50，假通过 4（01、02、25、48），假失败 1（44）
无参考评委 对 人工：19 条里假通过 8，一条“错”都没判
评委原样再判：19 条里 7 条结论变了
```

## 练习

1. 改 `harness.numbers()`：秒和毫秒统一单位、去掉“超过、约”再比，`run.py regrade base-@3-r1`，看“编造数字”从 4 条降到几条。
2. 说中词只在原因段里查，重判，看 25 号是不是不再假通过。
3. 不看 `labels.py`，用 `dump` 的输出自己标 50 条，算你和它的一致率。
