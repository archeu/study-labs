# 什么时候不该拆多 agent

对应页面：[什么时候不该拆多 agent](https://study.ainiya.de/agent/multi-agent-when-not.html)

| 文件 | 是什么 |
|---|---|
| `jstack_tokens.py` | 合成一份 200 个 Tomcat 线程的 jstack 和 2000 行访问日志，用本机 llama-server 的 `/tokenize` 数 token：真实规模的原文有多大 |
| `cost_model.py` | 拆不拆的估算模型：一个 agent 查 k 块材料 vs 主 agent + k 个子 agent，可选工具裁短、提示缓存、前后依赖、串行算力。页面实验台用同一套公式（JS 版） |

上一页 `multi-agent-orchestration/compare.py` 的实测账本是这一页的起点。

## 怎么跑

```bash
cd agent && uv sync
uv run python multi-agent-when-not/cost_model.py         # 不需要模型
uv run python multi-agent-when-not/jstack_tokens.py      # 需要本机 llama-server（见 langgraph-basics 的 README）
```

## 预期输出

`jstack_tokens.py`（2026-11-26，llama.cpp b12618，Qwen3 的分词器；Claude 的分词器数出来不一样，量级相同）：

```
jstack，200 个线程     180189 字符    65301 token
应用日志，2000 行      211981 字符   129981 token
```

`cost_model.py`（估算，不是实测）：

```
场景                       单:调用       输入       折算     最大上下文    耗时 |   多:调用       输入       折算    主最大     子最大    耗时    折算比
玩具现场（oncall.py）             4     3300     3300      1050    9s |      8     4500     4500   1050     550    9s   1.36
真实规模：每块 6.5 万 token         7   686700   686700    195600  186s |     11   297750   297750   1050   65400   35s   0.43
真实规模 + 开提示缓存                7   686700   244710    195600   75s |     11   297750   207840   1050   65400   28s   0.85
真实规模 + 工具裁短到 2000           7    25200    25200      6600   20s |     11    14250    14250   1050    2400   11s   0.57
裁短 + 缓存                     7    25200     8460      6600   16s |     11    14250     9390   1050    2400   11s   1.11
真实规模，子任务前后依赖                7   686700   686700    195600  186s |     11   299100   299100   1050   65700   97s   0.44
真实规模，本机串行算力                 7   686700   686700    195600  186s |     11   297750   297750   1050   65400   96s   0.43
```

“折算”把缓存命中的重读按 1/10 计（Anthropic 的缓存读取价是基础输入价的 10%），写缓存的加价没算，输出 token 也没算。

## 练习

先猜结果，再运行。

1. 把 `jstack_tokens.py` 里的线程栈只保留非 WAITING 的线程，token 降到多少？
2. 在 `cost_model.py` 里加一个场景：5 块、每块 2 万、前后依赖。拆开的耗时比一个 agent 短还是长？为什么页面上仍然说“别拆成并行的子 agent”？
3. 把 `OUT_TOOL`、`OUT_FINAL` 算进成本（输出价按输入价的 5 倍），结论有没有变？
