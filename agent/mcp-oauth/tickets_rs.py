"""tickets 的 Python 版，当 OAuth 的资源服务器（Resource Server）：没有有效令牌就 401，令牌不是发给自己的也 401。

    uv run python mcp-oauth/tickets_rs.py       # http://127.0.0.1:8768/mcp，先起 auth_server.py

- AuthSettings 让 SDK 自动提供 /.well-known/oauth-protected-resource（RFC 9728），并在 401 里带上它的地址
- 令牌怎么验：IntrospectionVerifier 拿令牌去授权服务器的 /introspect 问
- validate_token_resource=True：令牌的 audience（resource）必须是本服务端的 URL，防“拿别处的令牌来用”
- 写操作 add_comment 额外要 tickets:write
"""

import httpx2 as httpx

from mcp.server import MCPServer
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.provider import AccessToken
from mcp.server.auth.settings import AuthSettings
from mcp.server.mcpserver.exceptions import ToolError
from mcp_types import ToolAnnotations

AS = "http://127.0.0.1:9000"
ME = "http://127.0.0.1:8768/mcp"
COMMENTS = {"INC-1108": ["20:16 告警：/orders/{id} p99 > 2s"]}


class IntrospectionVerifier:
    async def verify_token(self, token: str) -> AccessToken | None:
        async with httpx.AsyncClient() as c:
            r = (await c.post(f"{AS}/introspect", data={"token": token})).json()
        if not r.get("active"):
            return None
        return AccessToken(token=token, client_id=r["client_id"], scopes=r["scope"].split(), expires_at=r["exp"],
                           resource=r.get("aud"), subject=r.get("sub"))


mcp = MCPServer("tickets", version="0.2.0", token_verifier=IntrospectionVerifier(),
                auth=AuthSettings(issuer_url=AS, resource_server_url=ME, required_scopes=["tickets:read"],
                                  validate_token_resource=True))


@mcp.tool(annotations=ToolAnnotations(read_only_hint=True))
def get_incident(id: str) -> str:
    """按工单号查故障工单的评论。工单号形如 INC-1108。"""
    who = get_access_token()
    if id not in COMMENTS:
        raise ToolError(f"没有工单 {id}")
    return f"{id}（查询人 {who.subject}）：" + " / ".join(COMMENTS[id])


@mcp.tool(annotations=ToolAnnotations(read_only_hint=False, destructive_hint=False))
def add_comment(id: str, text: str) -> str:
    """给故障工单加一条评论。会改工单，需要 tickets:write 权限。"""
    who = get_access_token()
    if "tickets:write" not in who.scopes:
        raise ToolError("令牌没有 tickets:write，不能写工单。请用带写权限的令牌重新授权。")
    COMMENTS.setdefault(id, []).append(f"{who.subject}: {text}")
    return f"已加到 {id}，共 {len(COMMENTS[id])} 条"


class WriteScopeGate:
    """按请求头做细粒度授权：Mcp-Name 是 add_comment 的调用，令牌里没有 tickets:write 就回 403 insufficient_scope，
    并在 WWW-Authenticate 里写明要什么 scope。客户端据此“升级授权”（step-up）后重试。不用拆请求体。"""

    def __init__(self, app):
        self.app, self.verifier = app, IntrospectionVerifier()

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            h = {k.decode().lower(): v.decode() for k, v in scope["headers"]}
            if h.get("mcp-method") == "tools/call" and h.get("mcp-name") == "add_comment":
                tok = h.get("authorization", "").removeprefix("Bearer ").strip()
                at = await self.verifier.verify_token(tok) if tok else None
                if at is not None and "tickets:write" not in at.scopes:
                    www = ('Bearer error="insufficient_scope", scope="tickets:read tickets:write", '
                           'resource_metadata="http://127.0.0.1:8768/.well-known/oauth-protected-resource/mcp", '
                           'error_description="add_comment requires tickets:write"')  # 响应头只能放 ASCII
                    await send({"type": "http.response.start", "status": 403,
                                "headers": [(b"www-authenticate", www.encode()), (b"content-type", b"application/json")]})
                    await send({"type": "http.response.body", "body": b'{"error":"insufficient_scope"}'})
                    return
        await self.app(scope, receive, send)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(WriteScopeGate(mcp.streamable_http_app(host="127.0.0.1")), host="127.0.0.1", port=8768, log_level="warning")
