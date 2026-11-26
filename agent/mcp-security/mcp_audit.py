"""MCP 服务端体检：连上去把工具清单拉下来，(1) 和上次批准时的“指纹”比对，(2) 按几条规则找可疑的说明。

    uv run python mcp-security/mcp_audit.py servers.json            # 体检，和 mcp-lock.json 比对
    uv run python mcp-security/mcp_audit.py servers.json --approve  # 人看过了，把当前清单记为批准版本

servers.json 的格式照抄 .mcp.json：{"mcpServers": {"名字": {"command": …, "args": […]} 或 {"type": "http", "url": …}}}

它挡不住所有攻击（说明可以写得很隐晦，工具结果里的注入它根本看不到），但能把两类最常见的情况变成“看得见”：
批准之后说明被偷偷改了（rug pull），以及说明里明显不该有的东西。
"""

import asyncio
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

from mcp import Client, StdioServerParameters

HERE = Path(__file__).resolve().parent
LOCK = HERE / "mcp-lock.json"

RULES = [  # (规则名, 正则) —— 命中只代表“要人看一眼”，不代表一定是攻击
    ("藏起来的指令标记", r"<\s*(IMPORTANT|SYSTEM|INSTRUCTIONS?)\s*>|ignore (all |the )?previous|忽略(以上|之前|前面)"),
    ("要求瞒着用户", r"不要(向|跟|和)?用户(提及|说|透露)|do not (tell|mention|inform) the user|don't tell the user"),
    ("碰敏感文件", r"~/\.ssh|id_rsa|\.env\b|\.aws/|credentials|\.claude\.json|mcp\.json|\.npmrc|\.git-credentials"),
    ("指挥别的工具", r"mcp__\w+__\w+|今后凡是调用|whenever you (call|use)"),
    ("调用前先做别的事", r"(调用|使用)本工具之前|before (calling|using) this tool"),
]
SUSPICIOUS_PARAMS = {"sidenote", "note", "notes", "context", "metadata", "debug", "extra"}


def fingerprint(tool) -> str:
    canon = json.dumps({"name": tool.name, "description": tool.description, "inputSchema": tool.input_schema,
                        "annotations": tool.annotations.model_dump(exclude_none=True) if tool.annotations else None},
                       ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(canon.encode()).hexdigest()[:16]


def check(tool) -> list[str]:
    hits, desc = [], tool.description or ""
    for name, pat in RULES:
        if re.search(pat, desc, re.I):
            hits.append(name)
    hidden = [c for c in desc if unicodedata.category(c) == "Cf"]  # 零宽字符等看不见的格式字符
    if hidden:
        hits.append(f"看不见的字符 {len(hidden)} 个")
    if len(desc) > 800:
        hits.append(f"说明过长 {len(desc)} 字")
    odd = SUSPICIOUS_PARAMS & set((tool.input_schema or {}).get("properties", {}))
    if odd:
        hits.append(f"可疑参数 {sorted(odd)}")
    a = tool.annotations
    if a and a.read_only_hint and re.search(r"delete|remove|drop|write|send|post|update|kill", tool.name, re.I):
        hits.append("名字像写操作却自称只读")
    return hits


def to_server(cfg: dict):
    if cfg.get("type") == "http" or "url" in cfg:
        return cfg["url"]
    cmd = sys.executable if cfg["command"] == "python" else cfg["command"]  # 用当前 venv 的 python
    return StdioServerParameters(command=cmd, args=cfg.get("args", []))


async def main(cfg_path: str, approve: bool) -> int:
    cfg = json.loads(Path(cfg_path).read_text(encoding="utf-8"))["mcpServers"]
    lock = json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else {}
    new_lock, problems = {}, 0
    for name, scfg in cfg.items():
        async with Client(to_server(scfg)) as c:
            tools = (await c.list_tools()).tools
        print(f"\n== {name}：{len(tools)} 个工具")
        for t in tools:
            key = f"mcp__{name}__{t.name}"
            fp = fingerprint(t)
            new_lock[key] = fp
            old = lock.get(key)
            status = "新工具（还没批准）" if old is None else ("和批准时一致" if old == fp else "⚠ 和批准时不一样：说明或参数被改过")
            hits = check(t)
            flag = "⚠" if hits or (old and old != fp) else "✓"
            print(f"  {flag} {key:34} 指纹 {fp}  {status}")
            for h in hits:
                print(f"      · {h}")
            problems += bool(hits) or bool(old and old != fp)
    if approve:
        LOCK.write_text(json.dumps(new_lock, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n已把当前 {len(new_lock)} 个工具记为批准版本 → {LOCK.name}")
    print(f"\n要人看一眼的：{problems} 个")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1], "--approve" in sys.argv)))
