# 出错怎么办：429 限流、过载、超时，退避加抖动

对应页面：[出错怎么办：429 限流、过载、超时，退避加抖动](https://study.ainiya.de/agent/api-errors-retry.html)

站 03 动手项“写一个会返回 429 的假服务，验证退避加抖动”。

- `fake_server.py`：标准库写的“假 Claude”，令牌桶每秒 5 个、桶容量 5，超了回 429 带 `retry-after: 1`；`model` 为 `fake-529-2` 时前 2 次回 529，`fake-slow` 时睡 3 秒；`GET /stats` 看计数。
- `main.py herd`：30 个线程同时请求，五种重试策略比请求数、429 数、完成时间、最挤的 100 毫秒。
- `main.py sdk`：官方 SDK 在 529 和超时下自动重试了几次、隔了多久。

## 怎么跑

```bash
cd agent && uv sync
python3 api-errors-retry/fake_server.py &        # 127.0.0.1:8097
uv run python api-errors-retry/main.py herd      # 约 40 秒
uv run python api-errors-retry/main.py sdk
```

## 预期输出

2026-11-22，2 核 ARM，Python 3.13，anthropic 2.12.0：

```
立即重试         成功 10/30  请求共  413 次（429  403 次）  全部成功用时   1.0 s  最挤的 100ms 里 160 个请求
固定 1 秒        成功 30/30  请求共  105 次（429   75 次）  全部成功用时   5.0 s
指数退避         成功 30/30  请求共  143 次（429  113 次）  全部成功用时  20.6 s
指数退避+全抖动  成功 30/30  请求共  149 次（429  119 次）  全部成功用时   8.0 s
按 retry-after+抖动 成功 30/30  请求共   78 次（429   48 次）  全部成功用时   5.3 s
529 两次后成功：SDK 一共发了 3 次请求，间隔 ['0.45s', '0.75s']
超时 1s、服务要 3s：SDK 发了 3 次，间隔 ['1.47s', '1.91s']，4.38s 后抛 APITimeoutError
```

“立即重试”时服务端会被打到重置连接，部分线程报 `ConnectionResetError`，这本身就是结论的一部分。随机数和线程调度会让数字每次略有不同。

## 练习

1. 给 `client()` 加 10 秒总截止时间，用“指数退避”看有几个被截掉。
2. 在 `cmd_sdk` 的 529 场景外面再包 3 次循环，数服务端一共收到多少次请求。
3. 30 个客户端共享一个“允许速率”：收到 429 减半、成功加 0.5，和“按 retry-after+抖动”比。
