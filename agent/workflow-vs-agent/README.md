# 工作流还是 Agent：五种常见模式怎么选

对应页面：[工作流还是 Agent：五种常见模式怎么选](https://study.ainiya.de/agent/workflow-vs-agent.html)

同一个告警，用“路由 + 固定流程 + 一次模型调用”来做：三条正则分类；“接口变慢”的流程把 since 按告警时间往前 15 分钟算，并发查四样证据，然后只调一次模型写结论。agent 版的数字来自全景页和 Agent 循环页的实测，这里不重跑。

## 怎么跑

先起本机模型（见 [pano-04-triage-agent](../pano-04-triage-agent/README.md)），或者连 Claude。

```bash
cd agent && uv sync
uv run python workflow-vs-agent/main.py
uv run python workflow-vs-agent/main.py --alert "21:05 告警：pay-svc 回调成功率 62%"   # 看路由
```

## 预期输出

2026-11-24，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M，anthropic 2.12.0，Python 3.13：

```
路由：接口变慢
并发查了 4 样证据：1.5 ms
模型调用 1 次：81.1s，输入 817 输出 426 token
总计 81.1s

结论：
1. 原因：导出操作导致内存占用过高，触发Full GC并造成响应延迟
2. 证据：GC日志显示7次停顿，3次Full GC（每次约1.9s），堆内存占用持续在68%-72%；……
3. 下一步：…… 或使用CMS收集器……        ← 过时的建议：CMS 在 JDK 14 已移除
```

对比：agent 全景页那次 5 次调用 349 秒，Agent 循环页那次 10 次调用约 681 秒。

## 练习

先猜结果，再运行。

1. 总结之后加一步评估-改进：按清单检查数字和建议，不合格带意见重写，看 CMS 那条能不能被抓到。
2. 让总结可以回答“证据不足”，此时把证据交给 Agent 循环页的 `agent()` 继续。
3. 用一次 `max_tokens=20` 的模型调用代替正则路由，拿 10 条告警比准确率。
