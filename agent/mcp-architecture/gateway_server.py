"""网关日志服务端：老同事两年前手写的，只会 2025-11-25 老协议（initialize 握手），不认识 server/discover。

只用标准库，40 行：MCP 服务端说到底就是“从 stdin 读一行 JSON-RPC，往 stdout 写一行回复”。
它也有一个叫 search_logs 的工具，和 oncall 的同名，主机要靠前缀分开。
"""

import json
import re
import sys

GATEWAY_LOG = [
    "2026-11-08 20:12:44 nginx upstream order-svc /orders/9527 504 2.11s",
    "2026-11-08 20:13:10 nginx upstream order-svc /orders/9531 499 2.39s",
    "2026-11-08 20:14:03 nginx upstream order-svc /orders/export 200 61.2s",
]
TOOLS = [{
    "name": "search_logs",
    "description": "查网关（nginx）访问日志里的慢请求和 5xx。返回最多 20 行。",
    "inputSchema": {"type": "object", "properties": {"keyword": {"type": "string", "description": "正则"}}, "required": ["keyword"]},
}]


def handle(method: str, params: dict) -> dict:
    if method == "initialize":
        return {"protocolVersion": "2025-11-25", "capabilities": {"tools": {}}, "serverInfo": {"name": "gateway", "version": "0.3.1"}}
    if method == "tools/list":
        return {"tools": TOOLS}
    if method == "tools/call" and params.get("name") == "search_logs":
        rows = [r for r in GATEWAY_LOG if re.search(params["arguments"]["keyword"], r)]
        return {"content": [{"type": "text", "text": "\n".join(rows) or "没查到"}], "isError": False}
    raise LookupError(method)


for line in sys.stdin:
    msg = json.loads(line)
    if "id" not in msg:  # 通知：不回
        continue
    try:
        out = {"jsonrpc": "2.0", "id": msg["id"], "result": handle(msg["method"], msg.get("params") or {})}
    except LookupError:
        out = {"jsonrpc": "2.0", "id": msg["id"], "error": {"code": -32601, "message": f"Method not found: {msg['method']}"}}
    sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
    sys.stdout.flush()
