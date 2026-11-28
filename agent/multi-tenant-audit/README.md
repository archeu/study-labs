# 多租户、权限、审计、脱敏

对应页面：[多租户、权限、审计、脱敏](https://study.ainiya.de/agent/multi-tenant-audit.html)

全景“大促夜里 1000 个告警同时涌进来”第 ①、⑤、⑧ 站：值班助手同时服务交易、支付、搜索三个团队时，同一个工具提议，执行器要按“谁在问”决定交出什么。

| 文件 | 做什么 |
|---|---|
| `tenancy.py` | 租户上下文（`contextvars`）；机器、日志、知识库都标了属于哪个团队；按团队 × 角色的工具权限；工具执行器（按租户限定数据范围、写审计）；带上一行哈希的防篡改审计；网关式正则脱敏和工具侧按字段脱敏（卡号过 Luhn） |
| `demo.py` | 6 个小实验，没有模型：模型“提议”的工具调用直接写在代码里 |

## 怎么跑

```bash
cd agent
uv run python multi-tenant-audit/demo.py     # 不到一秒
uv run pytest multi-tenant-audit/
```

## 预期输出

2026-11-28，Python 3.13.18：

```
== 1 支付团队的调查，模型提议查交易团队的机器 search_logs(order-04)
   不分租户：20:04:50 WARN GC pause 820ms ⏎ 20:04:52 INFO user 138****5678 placed order 9527
   按租户  ：[拒绝] order-04 不属于 pay
== 2 搜索团队查知识库 search_notes("pay-02 连接池")
   不分租户：pay-02 连接池打满：……账号 recon_admin / Pay#2026
   按租户  ：（没有结果）
== 3 交易团队：只读角色和负责人都提议 heap_dump(order-04)
   viewer：[拒绝] wang（trade/viewer）不能用 heap_dump
   lead  ：heap_dump of order-04 requested (等人批)
== 4 脱敏：网关式正则 对 工具侧按字段
   正则：20:04:53 INFO refund to card 6222 0202 0011 2233 446 ok, trace [卡号]
   字段：20:04:53 INFO refund to card [卡号 尾号3446] ok, trace 4111111111111111
== 5 审计防篡改：改掉一行，看校验在哪一行断
   写了 8 行，校验：全部对得上
   把第 2 行的 denied 改成 ok：校验在第 3 行断开
== 6 租户上下文跟不跟着走：协程、to_thread、run_in_executor
   create_task     → pay
   to_thread       → pay
   run_in_executor → LookupError：线程里没有租户
```

## 练习

先猜结果，再运行。

1. 给 `search_notes` 加一个“按租户的结果缓存”，故意把缓存键写成只有查询词：搜索团队先查，支付团队再查同一个词，会看到谁的笔记？
2. 把审计的“最后一行哈希”每分钟写到一个只追加的地方（比如另一份文件），再试着把最后两行一起改掉：还能发现吗？
3. 让 `run_in_executor` 也带上租户：用 `contextvars.copy_context().run(...)` 包一层。
4. 给 `mask_structured` 加身份证号（18 位，最后一位可能是 X，有校验码）。
