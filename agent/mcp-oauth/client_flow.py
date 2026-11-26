"""客户端视角走一遍 MCP 的 OAuth：每一个 HTTP 来回都打印出来。先起 auth_server.py 和 tickets_rs.py。

    uv run python mcp-oauth/client_flow.py

浏览器那一步用代码代替：redirect_handler 拿到授权地址后自己 GET 一下，
授权服务器（演示版）直接 302 回 redirect_uri?code=…&state=…&iss=…，callback_handler 把 code 交回 SDK。
"""

import asyncio
from urllib.parse import parse_qs, urlparse

import httpx2 as httpx

from mcp import Client
from mcp.client.auth import OAuthClientProvider
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.auth import AuthorizationCodeResult, OAuthClientInformationFull, OAuthClientMetadata, OAuthToken

SERVER = "http://127.0.0.1:8768/mcp"
REDIRECT = "http://127.0.0.1:3030/callback"
STEP = 0


class MemoryStorage:
    def __init__(self):
        self.tokens, self.client = None, None

    async def get_tokens(self) -> OAuthToken | None:
        return self.tokens

    async def set_tokens(self, tokens: OAuthToken) -> None:
        self.tokens = tokens

    async def get_client_info(self) -> OAuthClientInformationFull | None:
        return self.client

    async def set_client_info(self, client_info: OAuthClientInformationFull) -> None:
        self.client = client_info


async def log_response(r: httpx.Response) -> None:
    global STEP
    STEP += 1
    req = r.request
    path = req.url.path + (("?" + req.url.query.decode()[:70] + "…") if req.url.query else "")
    auth = " [带 Bearer]" if "authorization" in req.headers else ""
    extra = ""
    if r.status_code in (401, 403):
        extra = "  WWW-Authenticate: " + r.headers.get("www-authenticate", "")[:120]
    elif r.status_code in (301, 302, 303, 307):
        extra = "  → " + r.headers.get("location", "")[:90]
    print(f"{STEP:2}. {req.method:4} {req.url.host}:{req.url.port}{path}{auth}  ← {r.status_code}{extra}")


code_box: dict[str, str] = {}


async def redirect_handler(url: str) -> None:
    """本该打开浏览器让人登录；这里直接 GET 授权地址，拿 302 里的 code。"""
    q = parse_qs(urlparse(url).query)
    print(f"    （打开浏览器）response_type={q['response_type'][0]} code_challenge_method={q['code_challenge_method'][0]} "
          f"scope={q.get('scope', [''])[0]!r} resource={q.get('resource', [''])[0]}")
    async with httpx.AsyncClient(event_hooks={"response": [log_response]}) as c:
        r = await c.get(url)
    back = parse_qs(urlparse(r.headers["location"]).query)
    code_box.update(code=back["code"][0], state=back["state"][0], iss=back.get("iss", [None])[0])


async def callback_handler() -> AuthorizationCodeResult:
    return AuthorizationCodeResult(code=code_box["code"], state=code_box["state"], iss=code_box["iss"])


async def main() -> None:
    storage = MemoryStorage()
    auth = OAuthClientProvider(
        server_url=SERVER,
        client_metadata=OAuthClientMetadata(client_name="值班助手", redirect_uris=[REDIRECT],
                                            grant_types=["authorization_code", "refresh_token"], response_types=["code"],
                                            scope="tickets:read tickets:write"),
        storage=storage, redirect_handler=redirect_handler, callback_handler=callback_handler)
    http = httpx.AsyncClient(auth=auth, event_hooks={"response": [log_response]}, timeout=30)
    async with Client(streamable_http_client(SERVER, http_client=http)) as c:
        r = await c.call_tool("get_incident", {"id": "INC-1108"})
        print("\n→ get_incident:", r.content[0].text)
        r = await c.call_tool("add_comment", {"id": "INC-1108", "text": "根因：导出接口一次读 41 万行，Full GC 3 次"})
        print("→ add_comment:", r.content[0].text)
    await http.aclose()
    t = storage.tokens
    print(f"\n拿到的令牌：access {t.access_token[:10]}…（{t.expires_in}s），scope={t.scope!r}，refresh {t.refresh_token[:10]}…")


if __name__ == "__main__":
    asyncio.run(main())
