"""容器里跑的 worker：从 Redis Stream 领调查任务，一轮一轮跑（假模型），每轮写检查点；
收到 SIGTERM 时把手上这一轮做完、写好检查点再退出，不 XACK，留给别的 worker 用 XAUTOCLAIM 接手。

环境变量：REDIS_URL、ROUND_SEC（一轮多少秒，默认 4）、GATEWAY_KEY（虚拟 key，从 Secret 注入）、GRACEFUL（1/0）
"""
import json
import os
import signal
import sys
import time

import redis

R = redis.Redis.from_url(os.environ.get("REDIS_URL", "redis://127.0.0.1:6379/0"), decode_responses=True)
ROUND_SEC = float(os.environ.get("ROUND_SEC", "4"))
NAME = os.environ.get("HOSTNAME", "worker")
STREAM, GROUP = "deploy:jobs", "workers"
stopping = False


def log(**kv):
    print(json.dumps({"t": round(time.time(), 2), "worker": NAME, **kv}, ensure_ascii=False), flush=True)


def on_term(signum, frame):
    global stopping
    stopping = True
    log(event="SIGTERM", note="做完这一轮就退出")


if os.environ.get("GRACEFUL", "1") == "1":
    signal.signal(signal.SIGTERM, on_term)


def main():
    if not os.environ.get("GATEWAY_KEY"):
        log(event="no_key"); sys.exit(2)           # 没有密钥就别启动：比跑起来以后 401 好查
    try:
        R.xgroup_create(STREAM, GROUP, id="0", mkstream=True)
    except redis.ResponseError:
        pass
    log(event="start", key_suffix=os.environ["GATEWAY_KEY"][-4:])
    while not stopping:
        got = R.xreadgroup(GROUP, NAME, {STREAM: ">"}, count=1, block=1000)
        if not got:
            continue
        msg_id, f = got[0][1][0]
        inc = f["incident"]
        done = int(R.hget("deploy:ckpt", inc) or 0)
        for rnd in range(done + 1, 7):
            log(event="round_start", incident=inc, round=rnd)
            time.sleep(ROUND_SEC)                        # 假的模型调用 + 工具
            R.hset("deploy:ckpt", inc, rnd)              # 每轮写检查点
            log(event="round_done", incident=inc, round=rnd)
            if stopping:
                log(event="exit_graceful", incident=inc, round=rnd, note="检查点已写，不 XACK，等别人认领")
                return
        R.xack(STREAM, GROUP, msg_id)
        log(event="incident_done", incident=inc)


if __name__ == "__main__":
    main()
