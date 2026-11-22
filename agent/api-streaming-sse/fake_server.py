"""一个最小的“假 Claude”：只实现 POST /v1/messages 的流式返回，用来演示真模型不容易碰到的情况。

    python fake_server.py            # 127.0.0.1:8096

请求体里的 model 决定行为：
  fake-ok         正常流完 6 个文字增量
  fake-midstream  推了 3 个增量后，发一个 error 事件（overloaded_error），然后断开
只用标准库。
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PIECES = ["是，", "GC ", "导致的。", "依据：", "20:12:43 ", "Pause Full 1873ms"]


def sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # 安静点
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        mode = body.get("model", "fake-ok")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def send(b: bytes):
            self.wfile.write(f"{len(b):x}\r\n".encode() + b + b"\r\n")
            self.wfile.flush()

        send(sse("message_start", {"type": "message_start", "message": {
            "id": "msg_fake", "type": "message", "role": "assistant", "model": mode, "content": [],
            "stop_reason": None, "stop_sequence": None, "usage": {"input_tokens": 40, "output_tokens": 0}}}))
        send(sse("content_block_start", {"type": "content_block_start", "index": 0,
                                         "content_block": {"type": "text", "text": ""}}))
        for i, p in enumerate(PIECES):
            if mode == "fake-midstream" and i == 3:
                # HTTP 状态码早就发了 200，出错只能用一个 error 事件告诉客户端
                send(sse("error", {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}))
                send(b"")
                return
            time.sleep(0.3)
            send(sse("content_block_delta", {"type": "content_block_delta", "index": 0,
                                             "delta": {"type": "text_delta", "text": p}}))
        send(sse("content_block_stop", {"type": "content_block_stop", "index": 0}))
        send(sse("message_delta", {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                                   "usage": {"output_tokens": 18}}))
        send(sse("message_stop", {"type": "message_stop"}))
        send(b"")  # 长度为 0 的块：流结束


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8096
    print(f"假 Claude 已启动：http://127.0.0.1:{port}")
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
