"""工作流还是 Agent：同一个告警，用“路由 + 固定流程 + 一次模型调用”来做，和 agent 的实测比一比。

    python main.py                 # 跑工作流版：路由 → 并发查 3 样 → 模型写一次结论
    python main.py --alert "..."   # 换一个告警，看路由怎么分；分不到的交给 agent（只打印，不跑）

agent 版的数字来自全景页和 Agent 循环页的实测（同一台机器、同一个模型），这里不重跑。
"""

import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen3-1.7b")
client = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"), timeout=300)

# ---- 路由：规则就够；要更灵活可以换成一次便宜的模型分类调用 ----
ROUTES = [
    ("接口变慢", re.compile(r"P99|RT|耗时|超时|变慢")),
    ("错误率升高", re.compile(r"5xx|错误率|异常数")),
    ("资源打满", re.compile(r"CPU|内存|磁盘|连接池")),
]


def route(alert: str) -> str | None:
    return next((name for name, pat in ROUTES if pat.search(alert)), None)


def alert_minus_15(alert: str) -> str:
    h, m = map(int, re.search(r"(\d\d):(\d\d)", alert).groups())
    t = h * 60 + m - 15
    return f"{t // 60:02d}:{t % 60:02d}"


# ---- “接口变慢”这条线：SOP 的前三步写死，并发执行，不需要模型决定 ----
def slow_pipeline(alert: str) -> dict:
    since = alert_minus_15(alert)              # 行规写在代码里，不让模型填（工具怎么设计那页的落空就不会发生）
    jobs = {"慢请求": lambda: oncall.search_logs(r"耗时|超时|WARN|ERROR", since),
            "GC": lambda: oncall.parse_gc_log(since),
            "线程栈": oncall.thread_dump,
            "上线记录": lambda: oncall.search_logs("上线", "00:00")}
    with ThreadPoolExecutor(max_workers=4) as pool:
        futs = {k: pool.submit(f) for k, f in jobs.items()}
        return {k: f.result() for k, f in futs.items()}


SUMMARY_PROMPT = """你是值班排障助手。下面是按 SOP 查到的证据，请给结论：
1. 原因（一句话）；2. 证据（只引用下面出现过的数字）；3. 下一步（两条以内）。不要编造证据里没有的东西。"""


def workflow(alert: str):
    t0 = time.perf_counter()
    r = route(alert)
    print(f"路由：{r or '没有匹配的流程 → 交给 agent'}")
    if r != "接口变慢":
        return
    t1 = time.perf_counter()
    ev = slow_pipeline(alert)
    t2 = time.perf_counter()
    print(f"并发查了 {len(ev)} 样证据：{(t2 - t1) * 1000:.1f} ms")
    body = "\n".join(f"<{k}>\n{v}\n</{k}>" for k, v in ev.items())
    msg = client.messages.create(model=MODEL, max_tokens=2048, system=SUMMARY_PROMPT,
                                 messages=[{"role": "user", "content": f"告警：{alert}\n{body}"}])
    t3 = time.perf_counter()
    text = "".join(b.text for b in msg.content if b.type == "text").strip()
    print(f"模型调用 1 次：{t3 - t2:.1f}s，输入 {msg.usage.input_tokens} 输出 {msg.usage.output_tokens} token")
    print(f"总计 {t3 - t0:.1f}s\n\n结论：\n{text}")


if __name__ == "__main__":
    alert = sys.argv[sys.argv.index("--alert") + 1] if "--alert" in sys.argv else oncall.QUESTION
    workflow(alert)
