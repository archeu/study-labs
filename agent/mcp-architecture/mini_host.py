"""一个只用标准库的迷你主机：一个服务端配一个客户端，各自协商版本，工具加前缀合并，再各调一次。

    uv run python mcp-architecture/mini_host.py          # 先 discover，老服务端回退 initialize
    uv run python mcp-architecture/mini_host.py --legacy # 两个都只走 initialize

看三件事：
1. 主机（本脚本）持有对话和权限；每个 StdioClient 只认一个服务端进程。
2. 同一个主机里，两个客户端可以讲不同年代的协议（oncall 是新的，gateway 是老的）。
3. 服务端收到的只有自己的那几条请求，看不到“你的问题”，也看不到另一个服务端。
"""

import json
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVERS = {  # 相当于 .mcp.json
    "oncall": [sys.executable, str(HERE.parent / "pano-07-mcp-call" / "oncall_server.py")],
    "gateway": [sys.executable, str(HERE / "gateway_server.py")],
}
MODERN = "2026-07-28"
META = {"io.modelcontextprotocol/protocolVersion": MODERN,
        "io.modelcontextprotocol/clientCapabilities": {},
        "io.modelcontextprotocol/clientInfo": {"name": "mini-host", "version": "0.1"}}
T0 = time.perf_counter()


def ms() -> str:
    return f"{(time.perf_counter() - T0) * 1000:7.1f} ms"


class StdioClient:
    """一个客户端 = 一个子进程 + 一对管道。只和这一个服务端说话。"""

    def __init__(self, name: str, cmd: list[str]):
        self.name, self.era, self.next_id, self.seen = name, None, 1, []
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
        self.inbox: queue.Queue = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        for line in self.proc.stdout:
            try:
                self.inbox.put(json.loads(line))
            except json.JSONDecodeError:
                print(f"{ms()}  [{self.name}] 跳过一行不是 JSON 的输出：{line.strip()[:40]}")

    def _write(self, msg: dict) -> None:
        self.seen.append(msg.get("method"))
        self.proc.stdin.write(json.dumps(msg, ensure_ascii=False) + "\n")
        self.proc.stdin.flush()
        print(f"{ms()}  [{self.name}] → {msg.get('method')}  id={msg.get('id')}  {len(json.dumps(msg, ensure_ascii=False).encode())} B")

    def notify(self, method: str, params: dict | None = None) -> None:
        self._write({"jsonrpc": "2.0", "method": method, **({"params": params} if params else {})})  # 没有 id：对方不回

    def request(self, method: str, params: dict | None = None, timeout: float = 5.0) -> dict:
        params = dict(params or {})
        if self.era == "modern":
            params["_meta"] = META  # 新协议：每个请求自己带齐版本和能力
        rid, self.next_id = self.next_id, self.next_id + 1
        self._write({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        while True:
            msg = self.inbox.get(timeout=timeout)  # 超时抛 queue.Empty
            if msg.get("id") == rid:  # 按 id 配对
                kind = "error " + str(msg["error"]["code"]) if "error" in msg else "result"
                print(f"{ms()}  [{self.name}] ← {kind}  id={rid}  {len(json.dumps(msg, ensure_ascii=False).encode())} B")
                return msg

    def connect(self, legacy_only: bool) -> None:
        if not legacy_only:
            self.era = "modern"
            try:
                r = self.request("server/discover", timeout=3.0)
                if "result" in r and MODERN in r["result"].get("supportedVersions", []):
                    info = r["result"].get("_meta", {}).get("io.modelcontextprotocol/serverInfo", {})
                    print(f"{ms()}  [{self.name}] 新协议 {MODERN}，服务端自报 {info}，能力 {sorted(r['result']['capabilities'])}")
                    return
            except queue.Empty:
                pass  # 超时也算“不是新协议”
        self.era = "legacy"  # 回退：老协议的握手，只做一次，之后的请求不再带 _meta
        r = self.request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "mini-host", "version": "0.1"}})
        self.notify("notifications/initialized")
        print(f"{ms()}  [{self.name}] 老协议 {r['result']['protocolVersion']}，服务端自报 {r['result']['serverInfo']}")

    def close(self) -> None:
        self.proc.stdin.close()  # stdio 的“挂断”：关掉服务端的 stdin，等它自己退出
        self.proc.wait(timeout=5)


def main(legacy_only: bool) -> None:
    question = "order-svc 20:16 告警变慢，查一下根因"  # 只存在于主机里
    clients = {name: StdioClient(name, cmd) for name, cmd in SERVERS.items()}
    for c in clients.values():
        c.connect(legacy_only)

    tools = {}  # 主机把各家工具合并成一张表；同名工具靠前缀区分
    for name, c in clients.items():
        for t in c.request("tools/list")["result"]["tools"]:
            tools[f"mcp__{name}__{t['name']}"] = (c, t["name"])
    print(f"\n合并后的工具表（{len(tools)} 个）：", ", ".join(tools))

    # 模型那一步写死：两个服务端的 search_logs 各调一次
    for full, args in [("mcp__oncall__search_logs", {"keyword": "ERROR", "since": "20:06"}),
                       ("mcp__gateway__search_logs", {"keyword": "504|499"})]:
        c, real = tools[full]
        r = c.request("tools/call", {"name": real, "arguments": args})
        print(f"   {full} → {r['result']['content'][0]['text'][:60]}…")

    for c in clients.values():
        c.close()
    print(f"\n{ms()}  全部关闭。主机知道问题：“{question}”")
    for name, c in clients.items():
        print(f"   {name} 一共收到 {len(c.seen)} 条消息：{c.seen}")


if __name__ == "__main__":
    main("--legacy" in sys.argv)
