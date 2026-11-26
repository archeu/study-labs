# 业务流程自动化：写操作、审批、幂等、审计

对应页面：[业务流程自动化：写操作、审批、幂等、审计](https://study.ainiya.de/agent/business-write-ops.html)

全景第 8～11 站的最小实现：`approve` 节点 `interrupt(…, response_schema=Approval)` 等人批准（格式不对直接拒），`act` 节点按“告警指纹 + 动作 + 参数摘要”算幂等键去建工单，每一笔（含跳过的、被挡下的）都追加进审计表。不调模型。

| 文件 | 是什么 |
|---|---|
| `write_ops.py` | 三个剧本，每个都用“先查后写”和“唯一约束”两种工单库各跑一遍：`replay` 建完工单崩溃后重跑；`twice` 同一个批准被投递两次、两个 worker 同时恢复同一条线程；`badapprove` 批准里带了清单外的动作 |

## 怎么跑

```bash
cd agent && uv sync
uv run python business-write-ops/write_ops.py replay
uv run python business-write-ops/write_ops.py twice
uv run python business-write-ops/write_ops.py badapprove
```

## 预期输出

2026-11-26，2 核 ARM，Python 3.13.18，langgraph 1.2.24。`twice`：

```
[twice] 工单库用 check-then-act
    工单 2 张，审计 6 行：
      w1  create_ticket    7f3a:create_ticket:868b4ec4        已建 INC-1108
      w1  notify           7f3a:notify:e86ec2f0               notify 完成
      w2  create_ticket    7f3a:create_ticket:868b4ec4        已建 INC-1109
      w2  notify           7f3a:notify:e86ec2f0               notify 完成
      ……
[twice] 工单库用 unique
    工单 1 张，审计 6 行：
      w2  create_ticket    7f3a:create_ticket:868b4ec4        已建 INC-1108
      w2  notify           7f3a:notify:e86ec2f0               notify 完成
      w1  create_ticket    7f3a:create_ticket:868b4ec4        INC-1108（幂等键已存在，没再建）
      w1  notify           7f3a:notify:e86ec2f0               notify 完成
      ……
```

- `replay`：两种工单库都只建了 1 张。一个接一个的重跑，“先查后写”也挡得住。
- `twice`：两个 worker 同时查、同时写，“先查后写”建了 2 张；唯一约束只建 1 张。**通知两种都发了两次**：它没有幂等键。
- `badapprove`：`ValidationError`，线程仍停在 `approve`，改成合法的批准后正常跑完。

## 练习

先猜结果，再运行。

1. 给 `notify` 也加上幂等（比如一张 `sent` 表，主键是幂等键），再跑 `twice`，通知发几次？
2. 把幂等键改成 `uuid4()`，跑 `replay`，工单几张？
3. 把 `Approval` 改成 Pydantic 的 `BaseModel`，跑 `badapprove`，日志里多了什么警告？为什么页面建议用 TypedDict？
