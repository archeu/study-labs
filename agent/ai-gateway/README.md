# AI 网关：模型管理、token 限流、工具过滤

对应页面：[AI 网关：模型管理、token 限流、工具过滤](https://study.ainiya.de/agent/ai-gateway.html)

全景“大促夜里 1000 个告警同时涌进来”第 ⑤、⑧ 站：一个 200 来行的最小 AI 网关。对 worker 来说它就是供应商（Anthropic Messages 格式），官方 SDK 改一个 `base_url`、换一个虚拟 key 就能用。

| 文件 | 做什么 |
|---|---|
| `gateway.py` | 认虚拟 key → 租户能用的模型别名 → 预算 → 脱敏（手机号、卡号）→ 按白名单过滤请求里的 `tools` → 别名换真模型、带真 key 转发、529 换备用 → 检查回复里的 `tool_use` → 按 `usage` 记账（流式等最后的 `message_delta`）→ 写 `audit.jsonl` |
| `upstream.py` | 假供应商：`small` / `main` / `backup` 三个模型，支持流式；只认网关手里的真 key；消息里出现 heap_dump 时模型会“想调”它；`/control` 让 main 回 529 |
| `demo.py` | 9 个小实验，用官方 SDK 当 worker |

真 key 只出现在 `gateway.py` 的环境变量里；worker 手里只有 `vk-trade-7f3a` 这种虚拟 key。没有真模型，也不需要 API key。

## 怎么跑

```bash
cd agent && uv sync
uv run python ai-gateway/demo.py        # 约 30 秒
uv run pytest ai-gateway/
```

## 预期输出

2026-11-28，2 核 ARM，Python 3.13.18，anthropic 2.12.0，FastAPI 0.165.0，httpx 0.28.1：

```
== 1 经网关比直连多花多少时间（各 200 次，非流式，供应商固定 50 ms）
   直连：p50 52.7 ms，p99 54.1 ms
   经网关：p50 54.8 ms，p99 56.7 ms
== 2 模型别名：worker 只说 investigate，网关决定用哪个
   x-gateway-model = main
== 3 工具过滤：支付团队带了 heap_dump，日志里还有一句注入
   请求里被删掉的工具：['heap_dump']；回复里被拦下的 tool_use：['heap_dump']
   同样的请求换交易团队（白名单里有 heap_dump）：stop_reason=tool_use，tool_use=heap_dump
== 4 脱敏：供应商收到的：用户 [手机号] 用卡 [卡号] 支付失败，订单 9527（打码 2 处）
== 5 权限：不认识的 key 401 authentication_error；搜索团队用 investigate 403 permission_error
== 6 预算：第 1 次成功（$0.0012），第 2、3 次 429
== 7 流式：网关记账 output_tokens 40（来自最后的 message_delta）
== 8 主模型 529：x-gateway-model = backup
== 9 审计 211 行，按状态：{200: 207, 401: 1, 403: 1, 429: 2}
```

注意：anthropic SDK 2.x 把 key 放在 `Authorization: Bearer` 里发，不是 `x-api-key`。网关和假供应商两种都认。

## 练习

先猜结果，再运行。

1. 把上一页的 `FairBucket` 搬进网关，按租户做 token 限流，替换现在只有预算的第 3 层。
2. 给网关加一个按模型的熔断器（上上页的 `CircuitBreaker`），代替现在“遇到 529 就换”的写法：主模型坏的 3 分钟里，网关一共还会打主模型几次？
3. 起两个网关进程，各自记账：搜索团队的预算会被花超多少？怎么改成共享账本？
4. 脱敏正则会把 16 位以上的数字都当卡号：订单号、trace id 长这样时怎么办？
