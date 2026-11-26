"""同一个 oncall 服务端（全景页那份），换成 Streamable HTTP 传输：一个端点 POST /mcp。

    uv run python mcp-transports/oncall_http.py            # 127.0.0.1:8765，按请求选 JSON 或 SSE
    uv run python mcp-transports/oncall_http.py --json     # 一律回 application/json

工具代码一行没改，只是 run() 换了传输。只绑 127.0.0.1：本机用的 HTTP 服务端不该监听 0.0.0.0。
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-07-mcp-call"))
from oncall_server import mcp  # noqa: E402

if __name__ == "__main__":
    mcp.run("streamable-http", host="127.0.0.1", port=8765, json_response="--json" in sys.argv)
