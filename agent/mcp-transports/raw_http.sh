#!/usr/bin/env bash
# 不用 SDK，用 curl 敲 Streamable HTTP 的每一道关。先起服务端：uv run python mcp-transports/oncall_http.py
U=${U:-http://127.0.0.1:8765/mcp}
M='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}'
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2026-07-28')
show() { echo; echo "== $1"; shift; curl -s -i --max-time 3 "$@" | grep -iv '^date:\|^server:'; echo; }

show "1 完整的 tools/call" -X POST "$U" "${H[@]}" -H 'Mcp-Method: tools/call' -H 'Mcp-Name: search_logs' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_logs","arguments":{"keyword":"ERROR","since":"20:06"},'"$M"'}}'
show "2 少了 Mcp-Method（期望 400 / -32020）" -X POST "$U" "${H[@]}" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{'"$M"'}}'
show "3 Mcp-Name 和 body 不一致（期望 400 / -32020）" -X POST "$U" "${H[@]}" -H 'Mcp-Method: tools/call' -H 'Mcp-Name: heap_dump' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_logs","arguments":{"keyword":"ERROR"},'"$M"'}}'
show "4 Origin 不对（期望 403）" -X POST "$U" "${H[@]}" -H 'Origin: https://evil.example' -H 'Mcp-Method: tools/list' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{'"$M"'}}'
show "5 版本不认识（期望 400 / -32022）" -X POST "$U" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 1900-01-01' -H 'Mcp-Method: tools/list' \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"1900-01-01","io.modelcontextprotocol/clientCapabilities":{}}}}'
show "6 通知（期望 202）" -X POST "$U" "${H[@]}" -H 'Mcp-Method: notifications/cancelled' \
  -d '{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1,'"$M"'}}'
show "7 GET（规范建议 405）" "$U" -H 'Accept: text/event-stream'
show "8 老客户端 initialize（双年代服务端会开老式会话）" -X POST "$U" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":8,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"old","version":"1"}}}'
show "9 subscriptions/listen（SSE 流，3 秒后 curl 自己断开）" -X POST "$U" "${H[@]}" -H 'Mcp-Method: subscriptions/listen' \
  -d '{"jsonrpc":"2.0","id":9,"method":"subscriptions/listen","params":{'"$M"',"notifications":{"toolsListChanged":true}}}'
