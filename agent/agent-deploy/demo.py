"""部署的几个实测：镜像大小、密钥有没有进镜像、docker stop 时 worker 怎么退出。

前提：已经 build 了两个镜像（见 README），Redis 在 127.0.0.1:63710。
python3 agent-deploy/demo.py      # 需要 sudo docker；约 2 分钟
"""
import json
import subprocess
import time

import redis

R = redis.Redis(port=63710, decode_responses=True)
D = ["sudo", "docker"]


def sh(*args, check=True) -> str:
    return subprocess.run([*D, *args], capture_output=True, text=True, check=check).stdout.strip()


def reset():
    for k in ("deploy:jobs", "deploy:ckpt"):
        R.delete(k)


def run_and_stop(tag: str, graceful: str, label: str) -> None:
    reset()
    name = "s10-worker-demo"
    sh("rm", "-f", name, check=False)
    sh("run", "-d", "--name", name, "--network", "host", "-e", "REDIS_URL=redis://127.0.0.1:63710/0",
       "-e", "ROUND_SEC=4", "-e", f"GRACEFUL={graceful}", "-e", "GATEWAY_KEY=vk-pay-19c2-demo", tag)
    time.sleep(2)
    pid1 = sh("exec", name, "cat", "/proc/1/cmdline").replace("\x00", " ").strip()
    R.xadd("deploy:jobs", {"incident": "pay-gateway-PayCallbackSlow"})
    time.sleep(6)                                   # 第 1 轮做完，第 2 轮刚开始
    t = time.time()
    sh("stop", "-t", "10", name)
    took = time.time() - t
    code = sh("inspect", "-f", "{{.State.ExitCode}}", name)
    logs = [json.loads(l) for l in sh("logs", name).splitlines() if l.startswith("{")]
    events = [e["event"] + (f"#{e['round']}" if "round" in e else "") for e in logs]
    ckpt = R.hget("deploy:ckpt", "pay-gateway-PayCallbackSlow")
    pending = R.xpending("deploy:jobs", "workers")["pending"]
    print(f"{label}\n   1 号进程：{pid1}\n   docker stop 用了 {took:.1f} 秒，退出码 {code}；日志：{' → '.join(events)}\n"
          f"   检查点停在第 {ckpt} 轮，待确认 {pending} 条（留给别的 worker 认领）")
    sh("rm", "-f", name, check=False)


def main():
    print("== 1 镜像大小")
    for tag in ("naive", "slim"):
        print(f"   s10-worker:{tag:<6} {sh('images', 's10-worker:' + tag, '--format', '{{.Size}}')}")
    print("== 2 密钥有没有进镜像（docker history 里能不能看到）")
    for tag in ("naive", "slim"):
        hist = sh("history", "--no-trunc", "--format", "{{.CreatedBy}}", "s10-worker:" + tag)
        leak = [l for l in hist.splitlines() if "GATEWAY_KEY" in l]
        print(f"   {tag:<6}：{leak[0][:80] if leak else '没有'}")
    print("== 3 docker stop：worker 正在跑第 2 轮")
    run_and_stop("s10-worker:slim", "1", "slim 镜像，exec 形式 CMD，装了 SIGTERM 处理")
    run_and_stop("s10-worker:slim", "0", "slim 镜像，exec 形式 CMD，没装 SIGTERM 处理")
    run_and_stop("s10-worker:naive", "1", "naive 镜像，shell 形式 CMD，装了 SIGTERM 处理")
    reset()


if __name__ == "__main__":
    main()
