# 推理模型和“思考”：用 token 换质量

对应页面：[推理模型和“思考”：用 token 换质量](https://study.ainiya.de/agent/llm-reasoning-thinking.html)

同一道数数题（10 行 GC 日志，停顿超过 200ms 的有几次），Qwen3-0.6B 关掉思考 / 打开思考各跑 3 次，记录答案、计费的输出 token 和用时；再看 `max_tokens=300` 时思考把上限吃光。只用标准库。

## 准备

在 [llm-next-token](../llm-next-token/README.md) 编好的 llama.cpp 基础上，再下载 Qwen3-0.6B（约 397 MB），另起一个服务：

```bash
curl -LO https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
build/bin/llama-server -m Qwen3-0.6B-Q4_K_M.gguf --port 8091 -c 8192 -np 1 -t 2 --no-webui --jinja
```

## 怎么跑

```bash
cd agent
uv run python llm-reasoning-thinking/main.py --show   # 约 10 分钟（思考模式很慢）
```

## 预期输出

2026-11-22，2 核 ARM，llama.cpp b12618，Qwen3-0.6B Q4_K_M，Python 3.13：

```
日志 10 行，停顿超过 200ms 的真实答案：4 次
关掉思考 seed=1：答 8（错），计费的输出    8 个 token，用时   8.6 s
关掉思考 seed=2：答 3（错），计费的输出    8 个 token，用时   0.6 s
关掉思考 seed=3：答 3（错），计费的输出    8 个 token，用时   0.6 s
打开思考 seed=1：答 5（错），计费的输出 2503 个 token，用时 324.5 s
打开思考 seed=2：答 5（错），计费的输出  545 个 token，用时  60.1 s
打开思考 seed=3：答 3（错），计费的输出 1447 个 token，用时 151.7 s
打开思考、max_tokens=300：结束原因 length，看得见的回答 ''
```

seed=1 的思考里第一轮已经逐行数出“总共是 4 次”，之后自我怀疑、把第 6 行看成 312.530，最后交了 5。

**关于第一轮**：最早的题目写的是“最后一行只写数字”，模型把它理解成了“日志的最后一行”，思考里数出 5 却答了 240。那一轮作废，改成用 `<answer></answer>` 给答案、停顿值避开 200 附近后重跑，就是上面的结果。按实验预算只跑了这两轮。

## 练习

1. 先用正则取出超过 200ms 的行放进提示，再问“这几次分别是什么 GC”，关掉思考也答得对吗？
2. 关掉思考，在问题后加“请先逐行列出每次停顿并判断，再给出答案”（思维链提示），和内置思考比 token 数和正确率。
3. `max_tokens` 分别设 300、1000、3000，记录结束原因和有没有回答。
