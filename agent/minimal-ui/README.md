# 最小交互界面：流式输出、反馈按钮、转人工

对应页面：[最小交互界面：流式输出、反馈按钮、转人工](https://study.ainiya.de/agent/minimal-ui.html)

全景“大促夜里 1000 个告警同时涌进来”第 ⑨ 站：值班网页的最小版。后端一个 FastAPI 文件，前端一个 HTML 文件，没有前端框架。

| 文件 | 做什么 |
|---|---|
| `app.py` | 事件列表；`POST …/start` 开始调查（幂等键 + 按事件状态去重，GET 永远不触发）；`GET …/stream` 用 SSE 推进度和报告（每条带 id，支持 `Last-Event-ID`）；`…/feedback` 👍/👎 + 理由（同一个人只算最后一次）；`…/escalate` 转人工、带证据；`/api/eval-candidates` 把写了理由的 👎 导出成评测集候选 |
| `static/index.html` | 原生 JS：`EventSource` 收进度和报告，**用 `textContent` 写进页面**（报告里混了一段 HTML 和一个 Markdown 图片，不会被执行或加载） |
| `demo.py` | 用 httpx 当浏览器走 6 条路径 |

报告由假 agent 生成（固定剧本，按 token 一段段吐），不需要模型和 API key。

## 怎么跑

```bash
cd agent
uv run uvicorn --app-dir minimal-ui app:app --port 18760     # 浏览器打开 http://127.0.0.1:18760
uv run python minimal-ui/demo.py                             # 约 10 秒
uv run pytest minimal-ui/
```

## 预期输出

2026-11-28，2 核 ARM，Python 3.13.18，FastAPI 0.165.0：

```
== 0 页面：模型输出用 textContent 写进去（不是 innerHTML）：True
== 1 开始调查：双击、刷新都不会再起一次
   第一次点           → started=True
   双击（同一个键）       → started=False
   刷新后丢了键（新键）     → started=False
== 2 流式：进度 3 条 + 报告 60 段；第一条进度 0.40 秒，第一段报告 1.22 秒，整篇 2.47 秒
== 3 断线重连：第一次 10 条，重连补 54 条，id 连续不重复：True，拼出的报告和一次读完的一样：True
   报告里那段合作方备注原样是文字：True
== 4 反馈：zhang 投了两次，这个事件记 1 票；li 再投（没写理由），记 2 票；评测集候选 1 条
== 5 转人工：呼叫 oncall-pay，附 3 轮证据
```

第一版的去重只看“这个事件有没有吐出过东西”：agent 还没吐出第一条时刷新（换了新键），又起了一次调查，进度变成 6 条、报告两份交错在一起。现在按事件状态去重。

## 练习

1. 把 `index.html` 里写报告的 `textContent` 换成 `innerHTML`，在浏览器里打开：那段 `<img onerror>` 会怎样？（看完改回来）
2. 给 👎 加三个固定选项（结论错、证据不够、建议危险），再导出评测集候选，比较和自由填写的哪个更好用。
3. 把调查里的“把握”低于 0.6 时自动转人工，页面上标出“已自动转人工”。
4. 用 `curl -N` 连 `/stream`，中途 Ctrl-C，再带 `-H 'Last-Event-ID: 20'` 连一次。
