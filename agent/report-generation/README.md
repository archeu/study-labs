# 内容生成：带引用的长文报告

对应页面：[内容生成：带引用的长文报告](https://study.ainiya.de/agent/report-generation.html)

## 怎么跑

要 8091、8092、8093 三个 llama-server，见 [pano-06-runbook-to-report](../pano-06-runbook-to-report/README.md)。

```bash
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py
uv run python -W ignore report-generation/main.py        # 第一版：分节写 + 一次写完，本机约 6 分钟
uv run python -W ignore report-generation/main.py --v2   # 第二版，结果并进 report.json 的 v2
```

- 提纲（五节、每节要回答什么、去查什么）写死在 `OUTLINE` 里；每节单独检索（BM25 + 向量 + RRF，前 10 重排，留分数 > 0 的前 2），资料全局编号 S1、S2……
- `facts_from_timeline()` 从时间线算时间差；第二版的 `rule_check()` 再对照手册 §4.2 判断该不该回滚，都作为“计算事实”交给模型。
- 第二版另外：只用这次故障的单子（别的故障单不进候选）、提示里给编号格式示例、`[ S3 ]` 规范成 `[S3]`。
- `check()` 逐句回查：有没有编号、编号是不是这一节的资料、关键词覆盖。
- `labels.json`：三份报告逐句人工核对（ok 有依据且出处对、cite 出处错或没标、part 部分不准、bad 说错）。

## 预期输出

2026-11-25，2 核 ARM，Qwen3-1.7B Q4_K_M，llama.cpp b12618：

```
分节写（第一版）：{'sentences': 12, 'cited': 9, 'bad_ids': 0, 'flagged': 7}
一次写完：{'sentences': 18, 'cited': 7, 'bad_ids': 0, 'flagged': 11}   # 含 3 个标题
用时：检索 + 重排 52.6 s，分节生成 116.1 s，一次写完 114.0 s（提示 1181 token）
第二版：{'sentences': 15, 'cited': 15, 'bad_ids': 1, 'flagged': 2}
```

人工核对：一次写完 15 句里说错 3 句（“通过回滚解决”、改进项串台）；第一版 12 句里说错 4 句（算错时间、串台）；第二版 15 句全部带编号，8 句完全对，说错 2 句（“未使用降级开关”说反了，回查没抓到）。

## 练习

先猜结果，再运行。

1. 时间线一节改成代码直接渲染 S3 的表格，每行标 [S3]。
2. 改进项要求至少有一片章节名含“修复”的资料，否则写“资料未提及，待补充”。
3. 把含“符合 / 不符合 / 根因 / 应该 / 违反”的句子列成待人工确认清单。
4. 把句首的 [S#] 挪到句末再回查第一版。
