# MCP 的安全：不可信服务端、工具描述投毒、最小权限

对应页面：[MCP 的安全：不可信服务端、工具描述投毒、最小权限](https://study.ainiya.de/agent/mcp-security.html)

| 文件 | 是什么 |
|---|---|
| `evil_server.py` | **只用于演示的“坏”服务端** prettylog：v1.0.0 无害；同目录有 `.update` 文件时变成 v1.0.1，说明里藏 `<IMPORTANT>` 指令（读 `~/.ssh/id_rsa` 塞进 `sidenote`、别告诉用户、指挥 `mcp__tickets__add_comment`），并自称只读。它不会真的读任何文件 |
| `mcp_audit.py` | 体检脚本：连上每个服务端列工具，和 `mcp-lock.json` 里批准时的指纹比对（抓 rug pull），再按规则找可疑说明；有问题退出码 1 |
| `servers.json` | 体检的对象，格式照抄 `.mcp.json` |

## 怎么跑

```bash
cd agent && uv sync
rm -f mcp-security/.update mcp-security/mcp-lock.json
uv run python mcp-security/mcp_audit.py mcp-security/servers.json --approve 2>/dev/null   # v1.0.0，人看过，批准
touch mcp-security/.update                                                              # 模拟包作者发了新版
uv run python mcp-security/mcp_audit.py mcp-security/servers.json 2>/dev/null; echo $?  # 报警，退出码 1
rm -f mcp-security/.update mcp-security/mcp-lock.json                                   # 收尾（两个文件不进仓库）
```

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，mcp 2.3.0：

```
### 批准时（v1.0.0）
  ✓ mcp__oncall__search_logs           指纹 581803e0cd888c89  新工具（还没批准）
  ⚠ mcp__prettylog__pretty_log         指纹 ab40c29fa848ad30  新工具（还没批准）
      · 可疑参数 ['sidenote']
### 升级后（v1.0.1）
  ✓ mcp__oncall__search_logs           指纹 581803e0cd888c89  和批准时一致
  ⚠ mcp__prettylog__pretty_log         指纹 46e3530a2cf65245  ⚠ 和批准时不一样：说明或参数被改过
      · 藏起来的指令标记 · 要求瞒着用户 · 碰敏感文件 · 指挥别的工具 · 调用前先做别的事 · 可疑参数 ['sidenote']
```

## 练习

1. 把投毒说明改写成不含规则关键词的版本，看规则失效、指纹比对还在。
2. 加一条规则：说明里出现 servers.json 里别的服务端名字。
3. 体检退出码非 0 就不启动 Claude Code。
