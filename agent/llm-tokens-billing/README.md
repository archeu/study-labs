# token、分词和计费：同一段日志要花多少钱

对应页面：[token、分词和计费：同一段日志要花多少钱](https://study.ainiya.de/agent/llm-tokens-billing.html)

用本机 llama-server 里 Qwen2.5 的分词器（`/tokenize`）切文字、数 token，再乘 Claude 的单价估钱。只用标准库。
Claude 的分词器和 Qwen 不同；有 API key 时把 `count()` 换成 `client.messages.count_tokens(...)` 就是 Claude 的准数。

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 llama-server（8090 端口）。

```bash
cd agent
uv run python llm-tokens-billing/main.py split   # 几类文字各切成多少 token、切在哪
uv run python llm-tokens-billing/main.py log     # 61 行 GC 日志原样和瘦身后的 token 数和成本
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618（Qwen2.5-0.5B-Instruct 的分词器），Python 3.13：

```
类型     字符  字节 token  字符/token
英文句子    66    66    19     3.47
中文句子    30    68    23     1.30
GC 日志    112   112    78     1.44
Java 栈     61    61    15     4.07
JSON        79    79    42     1.88
UUID        36    36    35     1.03

原样 61 行：61 行，7417 个字符 → 整个请求 5008 个 token
   Claude Opus 5.5    一次 $0.02603   每天 2000 次 $52.06
瘦身后：28 行，1070 个字符 → 整个请求 801 个 token
   Claude Opus 5.5    一次 $0.00920   每天 2000 次 $18.41
```

## 练习

先猜结果，再运行。

1. 把 `slim()` 改成“只留 20:10 以后的原始行”，token 数和成本变成多少？丢了什么信息？
2. 模拟连续追问 10 轮、每轮新增 500 token、每次带上全部历史，打印每轮输入 token 和累计成本。
3. 拿 10 句中文值班报告算“字符数 ÷ token 数”的平均值和最小值，给“100 字以内”配一个够用的 `max_tokens`。
