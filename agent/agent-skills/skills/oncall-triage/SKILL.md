---
name: oncall-triage
description: order-svc 等 Java 服务告警变慢、超时、Full GC 频繁时的值班排障流程。先查告警前 10 分钟日志和上线记录，再看 GC，再看线程栈，结论必须带证据；需要 heap dump 先问人。用户说“告警了”“接口变慢”“帮我查根因”“值班排障”时使用。
---

# 值班排障：Java 服务变慢

按顺序做，每一步把看到的证据记下来，最后的结论逐条引用这些证据。

## 1. 圈定时间和范围

- 告警时间往前推 10 分钟作为起点（告警 20:16 → 从 20:06 查）。告警时间本身往往已经晚了。
- 确认是哪个服务、哪个接口：`/orders/{id}` 还是全部接口。

## 2. 日志和上线记录

- 用 `mcp__oncall__search_logs`，`keyword` 先用 `WARN|ERROR`，`since` 用上一步的起点。
- 再查一次上线记录：`keyword` 用 `上线|Controller`。告警前 30 分钟内有上线，优先怀疑它。

## 3. GC

- 拿到 GC 日志后，不要自己逐行读：运行 `scripts/gc_summary.py`，把日志从标准输入喂给它，只看它输出的摘要。
- 判断标准见 `references/gc-thresholds.md`（Full GC 次数、停顿、回收后堆占用的阈值）。只在摘要越过阈值时才去读它。

## 4. 线程栈

- 用 `mcp__oncall__thread_dump`。看 RUNNABLE 的线程停在哪个业务方法，WAITING 的在等什么（连接池、锁）。

## 5. 结论

- 格式：根因一句话 → 证据（日志行、GC 数字、线程栈位置）→ 建议。
- 需要 heap dump（`jmap -dump:live` 会触发 Full GC、停顿数秒）时，先问值班负责人，不要自己调。
- 要写进工单时用 `mcp__tickets__add_comment`，评论里只写结论和证据，不贴整段日志。
