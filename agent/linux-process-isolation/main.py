"""Linux 进程、权限与容器隔离：把一个“模型提议的命令”关进越来越小的笼子，每层拦住什么。

    python main.py            # 依次跑四层：普通子进程 → 资源限制 → namespace → 容器
    python main.py --layer 4  # 只跑某一层

需要：Linux；第 3 层要求内核允许非特权 user namespace（Ubuntu 22.04 默认允许）；
第 4 层要 docker 和 alpine:3.20 镜像（本机 docker 要 sudo，命令里带了 sudo）。
每个实验都是一条 shell 命令，打印命令、退出码和输出的第一行，方便和页面对照。
"""

import subprocess
import sys

DOCKER = ("sudo docker run --rm --network none --read-only --user 65534:65534 --cap-drop ALL "
          "--security-opt no-new-privileges --pids-limit 20 --memory 32m --memory-swap 32m alpine:3.20")

LAYERS = {
    1: ("普通子进程：和 agent 同一个用户、同一套权限", [
        ("能读你的家目录", "ls ~ | head -3"),
        ("能写你的家目录", "touch ~/agent-was-here && echo 写成功 && rm ~/agent-was-here"),
        ("能联网", "curl -s -m 5 -o /dev/null -w 'HTTP %{http_code}' https://example.com"),
        ("读不了 /etc/shadow（普通用户本来就不行）", "cat /etc/shadow"),
    ]),
    2: ("资源限制：timeout、prlimit（ulimit）", [
        ("超时：sleep 10 被 timeout 2 掐掉", "timeout 2 sleep 10"),
        ("内存：地址空间限 200 MB，申请 500 MB", "prlimit --as=200000000 python3 -c 'b = bytearray(500_000_000)'"),
        ("换用户 nobody：写不了你的家目录", "sudo -n -u nobody touch $HOME/x"),
    ]),
    3: ("namespace：看到的世界变小（不需要 root）", [
        ("PID namespace：只看得到自己", "unshare --user --map-root-user --pid --fork --mount-proc ps -e"),
        ("network namespace：只有一个关着的 lo", "unshare --user --map-root-user --net sh -c 'ip -brief link; curl -s -m 3 https://example.com || echo 连不上'"),
        ("user namespace 里的 root 不是真 root", "unshare --user --map-root-user sh -c 'id -u; cat /etc/shadow'"),
    ]),
    4: ("容器：namespace + cgroup + 只读根 + 去能力，一条 docker run", [
        ("身份是 nobody", f"{DOCKER} id"),
        ("断网", f"{DOCKER} sh -c 'wget -q -T 3 -O- http://example.com >/dev/null 2>&1 && echo 联网成功 || echo 联网失败'"),
        ("根目录只读", f"{DOCKER} touch /x"),
        ("进程数上限 20：fork 炸弹炸不开", f"{DOCKER} sh -c 'for i in $(seq 40); do sleep 3 & done; wait'"),
        ("内存上限 32 MB：吃 200 MB 被 OOM 杀掉", f"{DOCKER} sh -c 'head -c 200m /dev/zero | tail > /dev/null'"),
    ]),
}


def run(cmd: str) -> None:
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=60)
    out = (p.stdout + p.stderr).strip().splitlines()
    first = " / ".join(out[:3])[:110] if out else ""
    print(f"   $ {cmd if len(cmd) < 100 else cmd[:97] + '...'}\n     退出码 {p.returncode}  {first}")


def main():
    only = int(sys.argv[sys.argv.index("--layer") + 1]) if "--layer" in sys.argv else None
    for n, (title, cases) in LAYERS.items():
        if only and n != only:
            continue
        print(f"\n第 {n} 层 · {title}")
        for label, cmd in cases:
            print(f" · {label}")
            run(cmd)


if __name__ == "__main__":
    main()
