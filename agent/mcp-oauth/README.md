# 远程 MCP 的鉴权（OAuth）

对应页面：[远程 MCP 的鉴权（OAuth）](https://study.ainiya.de/agent/mcp-oauth.html)

全部用官方 Python SDK `mcp` 2.x 的积木在本机搭，不需要任何外部账号。**授权服务器是演示用的最小实现，不能用于生产。**

| 文件 | 是什么 |
|---|---|
| `auth_server.py` | 最小授权服务器（127.0.0.1:9000）：`create_auth_routes` 生成元数据、`/authorize`、`/token`、`/register`；内存存储；`/authorize` 直接当作“值班的你”已登录并同意；另加 `/introspect`（RFC 7662）和实验用的 `/lab/mint` |
| `tickets_rs.py` | 带鉴权的 tickets（127.0.0.1:8768/mcp）：`AuthSettings` + 令牌内省；`validate_token_resource=True` 只认发给自己的令牌；`WriteScopeGate` 按 `Mcp-Name` 头对 `add_comment` 要求 `tickets:write`，不够回 403 `insufficient_scope` |
| `client_flow.py` | 客户端：`OAuthClientProvider` 走完发现、注册、授权（PKCE S256 + resource）、换令牌、升级授权，每个 HTTP 来回都打印 |

## 怎么跑

```bash
cd agent && uv sync
uv run python mcp-oauth/auth_server.py     # 终端 1
uv run python mcp-oauth/tickets_rs.py      # 终端 2
uv run python mcp-oauth/client_flow.py     # 终端 3
# 收尾：终端 1、2 Ctrl+C；ss -ltn | grep -E '9000|8768' 应该没输出
```

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，mcp 2.3.0：

```
 1. POST 127.0.0.1:8768/mcp  ← 401  WWW-Authenticate: Bearer error="invalid_token", …, resource_metadata="…/.well-known/oauth-protected-resource/mcp"
 2. GET  127.0.0.1:8768/.well-known/oauth-protected-resource/mcp  ← 200
 3. GET  127.0.0.1:9000/.well-known/oauth-authorization-server  ← 200
 4. POST 127.0.0.1:9000/register  ← 201
    （打开浏览器）response_type=code code_challenge_method=S256 scope='tickets:read' resource=http://127.0.0.1:8768/mcp
 5. GET  127.0.0.1:9000/authorize?…  ← 302  → http://127.0.0.1:3030/callback?code=…&state=…
 6. POST 127.0.0.1:9000/token  ← 200
 7～9. POST 127.0.0.1:8768/mcp [带 Bearer]  ← 200
→ get_incident: INC-1108（查询人 oncall-you）：20:16 告警：/orders/{id} p99 > 2s
10. POST 127.0.0.1:8768/mcp [带 Bearer]  ← 403  WWW-Authenticate: Bearer error="insufficient_scope", scope="tickets:read tickets:write", …
11. GET  127.0.0.1:9000/authorize?…（scope='tickets:read tickets:write'） ← 302
12. POST 127.0.0.1:9000/token  ← 200
13. POST 127.0.0.1:8768/mcp [带 Bearer]  ← 200
→ add_comment: 已加到 INC-1108，共 2 条
```

curl 对照（令牌由 `/lab/mint` 签发）：发给 `:9999` 的令牌 → 401；没有 `tickets:read` → 403 `insufficient_scope`；令牌放查询串 → 401；正确令牌 → 200。

做这个实验时踩到的两个坑（页面 ⑥）：issuer 被 URL 类型补了一个斜杠，客户端按 RFC 8414 逐字比较后拒绝；动态注册时只申报了 `tickets:read`，升级授权被 `invalid_scope` 拒掉。

## 练习

1. `validate_token_resource=False` 后用 `aud=:9999` 的令牌调一次，应该变 200；想清楚风险，改回来。
2. `TTL` 改 10 秒，两次调用间 `sleep(15)`，trace 里应出现一次用刷新令牌的 `POST /token`。
3. 对没带令牌的请求回 401 并写 `scope="tickets:read"`，看客户端的授权请求是否照抄。
