"""会限流、会过载、会变慢的“假 Claude”：POST /v1/messages。只用标准库。

    python fake_server.py [端口]     # 默认 127.0.0.1:8097

限流：令牌桶，每秒补 RATE 个、最多存 BURST 个；没令牌就回 429，带 retry-after 头。
请求体里的 model 决定额外行为：
  fake-ok        正常（只受限流影响）
  fake-529-2     这个连接上前 2 次回 529 overloaded_error，之后正常（按请求体里的 metadata.user_id 计数）
  fake-slow      睡 3 秒再回（演示超时）
GET /stats 返回累计的 200 / 429 / 529 次数和每次请求到达的时间戳。
"""

import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RATE, BURST = 5.0, 5.0
lock = threading.Lock()
tokens, last = BURST, time.monotonic()
stats = {"200": 0, "429": 0, "529": 0, "arrivals": []}
seen_529 = {}
T0 = time.monotonic()


def take() -> bool:
    global tokens, last
    with lock:
        now = time.monotonic()
        tokens = min(BURST, tokens + (now - last) * RATE)
        last = now
        stats["arrivals"].append(round(now - T0, 3))
        if tokens >= 1:
            tokens -= 1
            return True
        return False


OK_BODY = {"id": "msg_fake", "type": "message", "role": "assistant", "model": "fake",
           "content": [{"type": "text", "text": "GC"}], "stop_reason": "end_turn", "stop_sequence": None,
           "usage": {"input_tokens": 20, "output_tokens": 1}}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def reply(self, code: int, body: dict, headers: dict | None = None):
        b = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith("/stats"):
            with lock:
                self.reply(200, stats)
            if "reset" in self.path:
                global T0
                with lock:
                    stats.update({"200": 0, "429": 0, "529": 0, "arrivals": []})
                    seen_529.clear()
                    T0 = time.monotonic()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        model = body.get("model", "fake-ok")
        if not take():
            with lock:
                stats["429"] += 1
            return self.reply(429, {"type": "error", "error": {"type": "rate_limit_error", "message": "rate limited"}},
                              {"retry-after": "1"})
        if model == "fake-529-2":
            uid = (body.get("metadata") or {}).get("user_id", "x")
            with lock:
                n = seen_529.get(uid, 0)
                seen_529[uid] = n + 1
            if n < 2:
                with lock:
                    stats["529"] += 1
                return self.reply(529, {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}})
        if model == "fake-slow":
            time.sleep(3)
        with lock:
            stats["200"] += 1
        self.reply(200, OK_BODY)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8097
    print(f"假 Claude（限流 {RATE:.0f}/s，桶 {BURST:.0f}）：http://127.0.0.1:{port}")
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
