# 为什么会一本正经地编（幻觉）

对应页面：[为什么会一本正经地编（幻觉）](https://study.ainiya.de/agent/llm-hallucination.html)

3 行 GC 日志、三道题（答案在日志里 / 不在日志里 / 要数），普通提示词、严格提示词、先查再答各问一遍，打印数字 token 的最低概率并用程序做粗核对。连本机 llama-server（Qwen2.5-0.5B），只用标准库。

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 llama-server（8090 端口）。

```bash
cd agent
uv run python llm-hallucination/main.py     # 约 30 秒
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M，温度 0，Python 3.13：

```
===== 普通版 =====
[日志里有] Full GC 那一次停顿了多少毫秒？（正确：1873.412ms）   答：198.204ms   最低概率 0.836
[日志里没有] order-04 用的是哪个 JDK 版本？（正确：日志里没有）  答：Java 11     最低概率 0.583
[要数] 这段日志里一共有几次 Young GC？（正确：2 次）              答：4次         最低概率 0.343
===== 严格版 =====  三题错法不变，一次 <quote> 也没写
===== 先查再答 =====
[日志里有]   答：1873.412ms
[日志里没有] 答：日志里没有（程序检索无命中，没有调用模型）
[要数]       答：1   （证据写着“Pause Young 一共 2 行”，转述错了）
```

概率的小数位重跑时会差几千分之几（服务端批处理），答案不变。

## 练习

1. 改写 `check()`：要求回答带 `<quote>`，检查数字出现在引用的那一行里。
2. “要数”那题改成程序数出 n、直接 `f"Young GC 共 {n} 次"`，模型只回答“这些停顿是否正常”。
3. 有 Claude 的 key 时，把 `ask()` 换成 Messages API 再跑一次三道题。
