"""全景：一次分诊调用的来回。官方 anthropic SDK，base_url 指向本机 llama-server（它实现了 /v1/messages）。

    python main.py              # 完整走一遍：组装 → 量长度 → 流式请求 → 收事件 → 校验 → 不合格就带着错误重试
    ANTHROPIC_BASE_URL= ANTHROPIC_API_KEY=sk-... MODEL=claude-opus-5-5 python main.py   # 有 key 时连 Claude

打印每一站的耗时和数字。本机模型是 Qwen2.5-0.5B，回答质量远不如 Claude，这里看的是链路。
"""

import json
import os
import re
import time
from typing import Literal

import anthropic
from pydantic import BaseModel, ValidationError

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen2.5-0.5b")
client = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"),
                             max_retries=2, timeout=60)


class Triage(BaseModel):                       # 第 9 站：结果长什么样，写成类型
    type: Literal["GC", "数据库", "支付", "前端", "其他"]
    level: Literal["P1", "P2", "P3"]
    cause: str
    first_check: str


ALERT = "【告警】2026-11-08 20:16:02 order-04 接口 P99 2.1s（阈值 800ms），持续 3 分钟"
GC_LOG = """\
[2026-11-08T20:12:03.512+0800][info][gc] GC(1213) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 236.871ms
[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms
[2026-11-08T20:13:10.033+0800][info][gc] GC(1215) Pause Young (Normal) (G1 Evacuation Pause) 905M->711M(1024M) 198.204ms"""

# 第 1 站：提示词 = 不变的规则和示例（放前面，好缓存）+ 这次的材料（用标签分开）+ 问题（放最后）
SYSTEM = """你是值班告警分诊助手。根据告警和日志，输出一个 JSON 对象，只输出 JSON，不要任何别的文字。
字段：type（GC/数据库/支付/前端/其他）、level（P1/P2/P3）、cause（一句话，可能原因）、first_check（一句话，先查什么）。

示例：
<alert>pay-02 回调成功率 62%</alert>
{"type": "支付", "level": "P1", "cause": "第三方支付回调验签失败", "first_check": "查回调验签日志和证书是否过期"}"""


def user_turn() -> str:
    return f"<alert>{ALERT}</alert>\n<gc_log>\n{GC_LOG}\n</gc_log>\n请分诊。"


def parse(text: str) -> Triage:
    m = re.search(r"\{.*\}", text, re.S)       # 模型常在 JSON 外面包 ```json，先抠出来
    if not m:
        raise ValueError("回答里没有 JSON 对象")
    return Triage.model_validate(json.loads(m.group(0)))


def call(messages: list) -> tuple[str, dict]:
    t0 = time.perf_counter()
    stats = {"events": {}, "ttft": None}
    with client.messages.stream(model=MODEL, max_tokens=300, system=SYSTEM, messages=messages) as s:
        for ev in s:                           # 第 7 站：SSE 事件一个个来
            stats["events"][ev.type] = stats["events"].get(ev.type, 0) + 1
            if ev.type == "content_block_delta" and stats["ttft"] is None:
                stats["ttft"] = time.perf_counter() - t0
        msg = s.get_final_message()
    stats.update(total=time.perf_counter() - t0, stop=msg.stop_reason, usage=msg.usage)
    return "".join(b.text for b in msg.content if b.type == "text"), stats


def main() -> None:
    print(f"模型：{MODEL} @ {BASE_URL or 'api.anthropic.com'}")
    messages = [{"role": "user", "content": user_turn()}]
    n = client.messages.count_tokens(model=MODEL, system=SYSTEM, messages=messages).input_tokens
    print(f"第 3 站 请求体：system {len(SYSTEM)} 字 + user {len(messages[0]['content'])} 字，count_tokens = {n}")
    for attempt in (1, 2, 3):
        text, st = call(messages)
        u = st["usage"]
        print(f"\n第 {attempt} 次调用：首字 {st['ttft']:.2f}s，总 {st['total']:.2f}s，stop_reason={st['stop']}，"
              f"usage 输入 {u.input_tokens} + 缓存读 {u.cache_read_input_tokens or 0}，输出 {u.output_tokens}")
        print(f"   事件：{st['events']}")
        print(f"   原文：{text.strip()[:160]}")
        try:
            t = parse(text)
            print(f"第 9 站 校验通过：{t.model_dump()}")
            break
        except (ValueError, ValidationError, json.JSONDecodeError) as e:
            err = str(e).splitlines()[0][:120]
            print(f"第 9 站 校验失败：{err}")
            # 多轮：把它的错回答和错误原因带回去，让它改（API 不记得上一轮，要整段重发）
            messages += [{"role": "assistant", "content": text},
                         {"role": "user", "content": f"上面的输出不合格：{err}。只输出符合要求的 JSON。"}]


if __name__ == "__main__":
    main()
