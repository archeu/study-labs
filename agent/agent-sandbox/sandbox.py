"""值班助手的 run_command：模型给一行命令，这里三道关：
  1. 白名单：只认几个只读命令，每个命令只认列出来的选项；不过 shell，所以 | ; & $( ) > < 一律不认
  2. 路径：所有文件参数必须落在 /logs 里（解析 .. 和符号链接之后再判断）
  3. 隔离执行：放行的命令在断网、只读、nobody、限内存限进程数的容器里跑，外面再套超时
"""

import shlex
import subprocess
import time
import uuid
from pathlib import PurePosixPath

LOG_ROOT = PurePosixPath("/logs")                 # 容器里的路径；宿主上的日志目录只读挂到这里
FORBIDDEN = set("|;&`$<>(){}\\\n")               # 出现这些字符，说明想拼 shell，直接拒

# 命令 → 允许的选项（带参数的选项写成 "-n="，表示后面要跟一个值）
ALLOW = {
    "grep": {"-c", "-i", "-E", "-m=", "-n"},
    "tail": {"-n="},
    "head": {"-n="},
    "wc":   {"-l"},
    "df":   {"-h"},
}
NEEDS_PATH = {"grep", "tail", "head", "wc"}       # 这些命令至少要一个 /logs 里的文件


class Rejected(Exception):
    pass


def normalize(p: str) -> PurePosixPath:
    """不碰文件系统的规范化：处理 . 和 ..（符号链接由容器里只挂 /logs 来兜底）。"""
    parts = []
    for seg in PurePosixPath(p).parts:
        if seg in ("/", "."):
            continue
        if seg == "..":
            if parts:
                parts.pop()
            continue
        parts.append(seg)
    return PurePosixPath("/", *parts)


def check(cmd: str) -> list[str]:
    """返回 argv；不合规就抛 Rejected，原因写成能直接回给模型的话。"""
    bad = sorted(set(cmd) & FORBIDDEN)
    if bad:
        raise Rejected(f"含有 shell 特殊字符 {''.join(bad)!r}：这里不经过 shell，一次只能跑一个命令")
    try:
        argv = shlex.split(cmd)
    except ValueError as e:
        raise Rejected(f"解析不了：{e}")
    if not argv:
        raise Rejected("空命令")
    prog, args = argv[0], argv[1:]
    if prog not in ALLOW:
        raise Rejected(f"{prog} 不在白名单里；能用的只有 {', '.join(ALLOW)}")
    allowed, paths, i = ALLOW[prog], [], 0
    while i < len(args):
        a = args[i]
        if a.startswith("-"):
            if a in allowed:
                i += 1
                continue
            if a + "=" in allowed and i + 1 < len(args) and args[i + 1].isdigit():
                i += 2
                continue
            raise Rejected(f"{prog} 不允许选项 {a}；允许的是 {', '.join(sorted(x.rstrip('=') for x in allowed))}")
        paths.append(a)
        i += 1
    if prog == "grep" and paths:                   # grep 的第一个非选项参数是模式，不是路径
        paths = paths[1:]
    for p in paths:
        real = normalize(p)
        if real != LOG_ROOT and LOG_ROOT not in real.parents:
            raise Rejected(f"路径 {p} 解析后是 {real}，不在 /logs 里")
    if prog in NEEDS_PATH and not paths:
        raise Rejected(f"{prog} 要指定 /logs 里的文件")
    return argv


DOCKER = ["sudo", "docker", "run", "--rm", "--network", "none", "--read-only", "--user", "65534:65534",
          "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", "20",
          "--memory", "32m", "--memory-swap", "32m", "--cpus", "0.5", "--env-file", "/dev/null"]


def run_in_container(argv: list[str], host_logs: str, timeout: float = 10) -> tuple[int, str, float]:
    name = f"sb-{uuid.uuid4().hex[:8]}"
    full = DOCKER + ["--name", name, "-v", f"{host_logs}:/logs:ro", "alpine:3.20", *argv]
    t0 = time.perf_counter()
    try:
        p = subprocess.run(full, capture_output=True, text=True, timeout=timeout, env={"PATH": "/usr/bin:/bin"})
        out = (p.stdout + p.stderr)[:1500]
        return p.returncode, out, time.perf_counter() - t0
    except subprocess.TimeoutExpired:
        subprocess.run(["sudo", "docker", "kill", name], capture_output=True)   # 超时：容器也要杀掉
        return 124, f"超过 {timeout} 秒，已终止", time.perf_counter() - t0


def run_command(cmd: str, host_logs: str) -> dict:
    """给 agent 用的执行函数：返回 tool_result 的 content 和 is_error。"""
    try:
        argv = check(cmd)
    except Rejected as e:
        return {"is_error": True, "content": f"没有执行：{e}"}
    code, out, dt = run_in_container(argv, host_logs)
    return {"is_error": code not in (0, 1), "content": f"退出码 {code}，{dt:.2f}s\n{out.strip()}"}
