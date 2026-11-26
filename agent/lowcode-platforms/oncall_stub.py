"""值班助手的桩：只收 POST /incidents，记下收到了什么、什么时候收到的。代替全景第 2 站的 FastAPI + LangGraph。

    python3 lowcode-platforms/oncall_stub.py          # 监听 127.0.0.1:8099，收到的写进 data/incidents.jsonl
"""

import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

LOG = Path(__file__).parent / "data" / "incidents.jsonl"


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        LOG.parent.mkdir(exist_ok=True)
        with LOG.open("a") as f:
            f.write(json.dumps({"at": time.time(), "path": self.path, "body": body}, ensure_ascii=False) + "\n")
        print(f"{time.strftime('%H:%M:%S')}  收到 {self.path}：{body}", flush=True)
        self.send_response(202)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"thread_id": body.get("thread_id"), "status": "investigating"}).encode())

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8099), Handler).serve_forever()
