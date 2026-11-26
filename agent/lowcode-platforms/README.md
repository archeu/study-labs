# 低代码平台：Dify、Coze、n8n

对应页面：[低代码平台：Dify、Coze、n8n](https://study.ainiya.de/agent/lowcode-platforms.html)

全景第 1 站用 n8n 搭出来：Alertmanager 的 Webhook → 每条告警拆成一项 → 跨执行去重 → POST 给值班助手。值班助手用一个桩服务代替。

| 文件 | 是什么 |
|---|---|
| `alert-to-oncall.json` | n8n 工作流（4 个节点），按 `fingerprint` 去重 |
| `alert-to-oncall-v2.json` | 修正版：按 `fingerprint@startsAt` 去重，thread_id 带上触发时间 |
| `oncall_stub.py` | 值班助手的桩：收 `POST /incidents`，记进 `data/incidents.jsonl` |
| `send_alerts.py` | 模拟 Alertmanager：同一告警发三次（首次、状态变化重发、repeat_interval 重发）+ 另一个告警 |

## 怎么跑

```bash
cd agent/lowcode-platforms
mkdir -p /tmp/n8n-data && chmod 777 /tmp/n8n-data && cp alert-to-oncall*.json /tmp/n8n-data/
IMG=n8nio/n8n:2.49.7      # 1.32 GB
docker run --rm -v /tmp/n8n-data:/home/node/.n8n --entrypoint n8n $IMG import:workflow --input=/home/node/.n8n/alert-to-oncall.json
docker run --rm -v /tmp/n8n-data:/home/node/.n8n --entrypoint n8n $IMG publish:workflow --id=alertToOncall0001   # 2.x：导入的是草稿，要发布
python3 oncall_stub.py &
docker run -d --name n8n --network host -v /tmp/n8n-data:/home/node/.n8n -e N8N_DIAGNOSTICS_ENABLED=false $IMG
python3 send_alerts.py
# 编辑器：http://localhost:5678 （第一次打开要建管理员账号）
```

## 预期输出

2026-11-26，2 核 ARM，n8n 2.49.7（Docker，host 网络）：

```
发 首次触发                     fingerprint=7f3a  Webhook 回应 79 ms
发 20:21 状态变化，重发            fingerprint=7f3a  Webhook 回应 54 ms
发 4 小时后 repeat_interval 重发 fingerprint=7f3a  Webhook 回应 30 ms
发 另一个告警                    fingerprint=9c21  Webhook 回应 25 ms

发了 4 次，值班助手收到 2 次： ['alert-7f3a', 'alert-9c21']
```

再发一条“下周同一个告警再响”（同一个 fingerprint，startsAt 2026-11-15 09:02）：值班助手新收到 **0 次**。Alertmanager 的 fingerprint 是按标签算的，同一个告警下次再响还是它。

修正版（`alertmanager-v2`）同样发四次（三次同一触发 + 下周再响）：

```
发了 4 次，值班助手收到 2 次： ['alert-7f3a-202611082016', 'alert-7f3a-202611150902']
```

Webhook 节点设的是 `responseMode: onReceived`：收到就回 200，后面的节点失败了 Alertmanager 也不知道。而且去重节点在转发之前就记住了事件键：停掉 `oncall_stub.py` 发一个新告警（b77e），再起桩、重发同一个，值班助手收到 **0 次**。

## 练习

先猜结果，再运行。

1. 在编辑器里打开工作流，把“按指纹去重”的 Scope 从默认改成 Workflow，再发一遍，结果变吗？
2. 停掉 `oncall_stub.py` 再发一个新告警：Alertmanager 收到的是 200 还是错误？去 n8n 的 Executions 页看这次执行。
3. 把 Webhook 的 `responseMode` 改成 `lastNode`，再停掉桩发一次，Alertmanager 这回收到什么？
