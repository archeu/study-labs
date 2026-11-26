"""把站 04 的工具包成 LangChain 的 tool：函数签名 + 文档字符串就是发给模型的说明书。
现场数据（2026-11-08 20:16 order-04 变慢）沿用站 04 的 oncall.py。"""

import os
import sys
from pathlib import Path

from langchain_anthropic import ChatAnthropic
from langchain_core.tools import tool

sys.path.insert(0, str(Path(__file__).parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402


@tool
def search_logs(keyword: str, since: str = "20:00") -> str:
    """在 order-svc 应用日志里按正则查某段时间的日志行，查报错、慢请求、上线记录时用。
    keyword 例如 WARN|ERROR、耗时、Export；since 是开始时间 HH:MM，告警前 15 分钟，例如告警 20:16 就填 20:00。"""
    return oncall.search_logs(keyword, since)


@tool
def parse_gc_log(since: str = "20:00") -> str:
    """解析 order-svc 的 GC 日志：停顿次数、最长停顿、Full GC 次数、Full GC 后堆还占多少。怀疑 GC 导致变慢时用。
    since 是开始时间 HH:MM，告警前 15 分钟。"""
    return oncall.parse_gc_log(since)


@tool
def thread_dump() -> str:
    """对 order-svc 做一次线程栈快照（jstack），返回按状态归类的摘要。看线程卡在哪时用。"""
    return oncall.thread_dump()


TOOLS = [search_logs, parse_gc_log, thread_dump]
SYSTEM = oncall.SYSTEM
QUESTION = oncall.QUESTION


def make_model() -> ChatAnthropic:
    """默认连本机 llama-server（实现了 Claude 的 /v1/messages）；设了 ANTHROPIC_API_KEY 和 MODEL 就连真 Claude。"""
    if os.environ.get("ANTHROPIC_API_KEY"):
        return ChatAnthropic(model=os.environ["MODEL"], max_tokens=1024)
    return ChatAnthropic(
        model="qwen3-1.7b", base_url="http://127.0.0.1:8090", api_key="local", max_tokens=700, temperature=0,
        model_kwargs={"extra_body": {"chat_template_kwargs": {"enable_thinking": False}}},  # 关掉思考，一轮 4～10 秒
    )
