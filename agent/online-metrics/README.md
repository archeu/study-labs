# 线上指标：成功率、成本、P95 延迟、转人工率

对应页面：[线上指标：成功率、成本、P95 延迟、转人工率](https://study.ainiya.de/agent/online-metrics.html)

本机没有线上流量：用 [eval-dataset](../eval-dataset/README.md) 存下的真实运行结果回放一天（或几天）的告警，指标用 OpenTelemetry 指标 SDK（1.45.2）按 GenAI 约定的名字记：

```
gen_ai.invoke_agent.duration                 直方图（约定建议的桶边界）
gen_ai.client.inference.usage.input_tokens   计数器（含缓存命中）
gen_ai.client.inference.usage.output_tokens  计数器
oncall.requests{outcome=adopted|escalated|ignored}   自定义：值班同学的反应
```

都带 `prompt_version`。P95 照 Prometheus 的 `histogram_quantile` 从桶里插值。

**假设**（代码开头）：一天 480 次告警；值班同学对“判分全对”的结论 90% 采纳、5% 转人工，“说中但没全对”50% / 25%，其余 15% / 60%。耗时、token、对错是真实的，采纳和转人工是按假设抽的。

## 怎么跑

```bash
cd agent
uv run python online-metrics/main.py replay --canary fix-@5-r1
uv run python online-metrics/main.py replay --canary fix-@5-r1 --days 7
uv run python online-metrics/main.py replay --canary pr57-@4-r1
uv run python online-metrics/main.py buckets base-@3-r1
```

## 预期输出

```
@5 灰度 5% 一天：@5 请求 23，成功率 26.1%（13～46），@3 22.3%（19～26）——分不开
@5 灰度 5% 七天：@5 请求 167，成功率 26.3%（20～34），@3 24.4%（23～26）——仍重叠
假如 @4 灰度一天：@4 成功率 34.8%（19～55，运气），P95 真实 109s → 132s，直方图估 117s → 197s
buckets base-@3-r1：真实 P95 92.6s，直方图插值 115.0s（耗时全挤在 60～120 秒一个桶里）
```

## 练习

1. View 里把 60～120 秒之间每 10 秒加一个桶边界，重跑 `buckets`。
2. `replay --days 3`，看 @5 的成功率区间变窄多少。
3. 把 `REACT` 里“没说中”的采纳率改成 40%，看两版的差距怎么变。
