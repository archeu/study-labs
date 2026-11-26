"""一个最小的授权服务器（Authorization Server），只为看清 MCP 的 OAuth 流程。不能用于生产。

    uv run python mcp-oauth/auth_server.py      # http://127.0.0.1:9000

- 端点由官方 SDK 的 create_auth_routes 生成：/.well-known/oauth-authorization-server、/authorize、/token、/register
- 存储全在内存里；/authorize 不弹登录页，直接当作“值班的你”已经登录并同意（真实环境是公司 SSO）
- 另加一个 /introspect（RFC 7662）：资源服务器拿令牌来问“这是谁、能干什么、发给谁的”
- 令牌绑定 resource（RFC 8707）：发给 tickets 的令牌，别的服务端拿去用会被拒
"""

import secrets
import time

import uvicorn
from pydantic import AnyHttpUrl, BaseModel, ConfigDict
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from mcp.server.auth.provider import AccessToken, AuthorizationCode, AuthorizationParams, RefreshToken, construct_redirect_uri
from mcp.server.auth.routes import create_auth_routes
from mcp.server.auth.settings import ClientRegistrationOptions
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

ISSUER = "http://127.0.0.1:9000"
USER = "oncall-you"  # “值班的你”
TTL = 300  # 访问令牌 5 分钟


class MemoryProvider:
    def __init__(self) -> None:
        self.clients: dict[str, OAuthClientInformationFull] = {}
        self.codes: dict[str, AuthorizationCode] = {}
        self.tokens: dict[str, AccessToken] = {}
        self.refresh: dict[str, RefreshToken] = {}

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        return self.clients.get(client_id)

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        # 客户端注册时只申报了当时要的 tickets:read；之后 step-up 要 tickets:write 会被 /authorize 以 invalid_scope 拒掉
        # （页面 ⑥ 坑 3，实测）。这里按授权服务器自己的策略：这个客户端允许申请两种，给不给由用户同意时决定。
        print(f"[AS] 注册客户端 {client_info.client_name!r}，申报 scope={client_info.scope!r}，按策略放宽为两种", flush=True)
        client_info.scope = "tickets:read tickets:write"
        self.clients[client_info.client_id] = client_info

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        code = secrets.token_urlsafe(16)
        self.codes[code] = AuthorizationCode(
            code=code, scopes=params.scopes or [], expires_at=time.time() + 60, client_id=client.client_id,
            code_challenge=params.code_challenge, redirect_uri=params.redirect_uri,
            redirect_uri_provided_explicitly=params.redirect_uri_provided_explicitly, resource=params.resource, subject=USER)
        print(f"[AS] {USER} 同意授权 scopes={params.scopes} resource={params.resource}", flush=True)
        return construct_redirect_uri(str(params.redirect_uri), code=code, state=params.state, iss=ISSUER)

    async def load_authorization_code(self, client, authorization_code: str) -> AuthorizationCode | None:
        return self.codes.get(authorization_code)

    def _issue(self, client_id: str, scopes: list[str], resource: str | None, subject: str | None) -> OAuthToken:
        at, rt = "at_" + secrets.token_urlsafe(24), "rt_" + secrets.token_urlsafe(24)
        self.tokens[at] = AccessToken(token=at, client_id=client_id, scopes=scopes, expires_at=int(time.time()) + TTL,
                                      resource=resource, subject=subject)
        self.refresh[rt] = RefreshToken(token=rt, client_id=client_id, scopes=scopes, resource=resource, subject=subject)
        print(f"[AS] 发令牌 {at[:10]}… scopes={scopes} aud={resource} {TTL}s", flush=True)
        return OAuthToken(access_token=at, expires_in=TTL, scope=" ".join(scopes), refresh_token=rt)

    async def exchange_authorization_code(self, client, authorization_code: AuthorizationCode) -> OAuthToken:
        self.codes.pop(authorization_code.code, None)  # 授权码只能用一次
        return self._issue(client.client_id, authorization_code.scopes, authorization_code.resource, authorization_code.subject)

    async def load_refresh_token(self, client, refresh_token: str) -> RefreshToken | None:
        return self.refresh.get(refresh_token)

    async def exchange_refresh_token(self, client, refresh_token: RefreshToken, scopes: list[str]) -> OAuthToken:
        self.refresh.pop(refresh_token.token, None)  # 公开客户端的刷新令牌要轮换
        return self._issue(client.client_id, scopes or refresh_token.scopes, refresh_token.resource, refresh_token.subject)

    async def load_access_token(self, token: str) -> AccessToken | None:
        t = self.tokens.get(token)
        return t if t and (t.expires_at is None or t.expires_at > time.time()) else None

    async def revoke_token(self, token) -> None:
        self.tokens.pop(token.token, None)
        self.refresh.pop(token.token, None)


provider = MemoryProvider()


async def introspect(request: Request) -> JSONResponse:
    """RFC 7662：资源服务器来问令牌的情况。真实环境这里要认证资源服务器本身。"""
    form = await request.form()
    t = await provider.load_access_token(str(form.get("token", "")))
    if t is None:
        return JSONResponse({"active": False})
    return JSONResponse({"active": True, "client_id": t.client_id, "scope": " ".join(t.scopes), "exp": t.expires_at,
                         "sub": t.subject, "aud": t.resource, "iss": ISSUER})


async def mint(request: Request) -> JSONResponse:
    """实验用后门：直接发一个指定 scope / audience 的令牌，用来演示 403 和 audience 校验。"""
    q = request.query_params
    tok = provider._issue("lab", q.get("scope", "").split(), q.get("aud"), USER)
    return JSONResponse({"access_token": tok.access_token})


class _Url(BaseModel):
    """issuer 必须和资源服务器元数据里写的一字不差。AnyHttpUrl("http://127.0.0.1:9000") 会补成 ".../"，
    客户端按 RFC 8414 逐字比较就会拒绝（页面 ⑥ 坑 1，实测）。所以保留空路径，不补斜杠。"""
    model_config = ConfigDict(url_preserve_empty_path=True)
    u: AnyHttpUrl


routes = create_auth_routes(provider, _Url(u=ISSUER).u, client_registration_options=ClientRegistrationOptions(
    enabled=True, valid_scopes=["tickets:read", "tickets:write"], default_scopes=["tickets:read"]))
routes += [Route("/introspect", introspect, methods=["POST"]), Route("/lab/mint", mint, methods=["GET"])]
app = Starlette(routes=routes)

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=9000, log_level="warning")
