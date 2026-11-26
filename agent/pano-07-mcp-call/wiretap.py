"""stdio 窃听器：夹在 MCP 客户端和服务端中间，原样转发两个方向的每一行，同时记进 JSONL。

用法：把客户端配置里的 command 换成
    python wiretap.py <日志文件> <真正的服务端命令...>
例如 Claude Code 的 .mcp.json：
    {"mcpServers": {"oncall": {"command": "uv", "args": ["run", "--project", "agent", "python",
        "agent/pano-07-mcp-call/wiretap.py", "/tmp/wire.jsonl", "python", "agent/pano-07-mcp-call/oncall_server.py"]}}}

日志每行：{"t": 距启动毫秒, "dir": "c2s" | "s2c", "msg": 原始 JSON}
只用标准库，线程转发，不改任何字节。
"""

import json
import subprocess
import sys
import threading
import time


def main() -> None:
    log_path, cmd = sys.argv[1], sys.argv[2:]
    t0 = time.perf_counter()
    lock = threading.Lock()
    log = open(log_path, "a", encoding="utf-8")

    def record(direction: str, line: bytes) -> None:
        text = line.decode("utf-8", "replace").rstrip("\n")
        try:
            msg = json.loads(text)
        except json.JSONDecodeError:
            msg = {"_raw": text}
        with lock:
            log.write(json.dumps({"t": round((time.perf_counter() - t0) * 1000, 1), "dir": direction, "msg": msg}, ensure_ascii=False) + "\n")
            log.flush()

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE)  # stderr 直接继承

    def pump_in() -> None:  # 客户端 → 服务端
        for line in sys.stdin.buffer:
            record("c2s", line)
            proc.stdin.write(line)
            proc.stdin.flush()
        proc.stdin.close()  # 客户端关了 stdin：服务端该退出了

    def pump_out() -> None:  # 服务端 → 客户端
        for line in proc.stdout:
            record("s2c", line)
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()

    threading.Thread(target=pump_in, daemon=True).start()
    out = threading.Thread(target=pump_out)
    out.start()
    code = proc.wait()
    out.join()
    with lock:
        log.write(json.dumps({"t": round((time.perf_counter() - t0) * 1000, 1), "dir": "exit", "msg": {"code": code}}) + "\n")
    sys.exit(code)


if __name__ == "__main__":
    main()
