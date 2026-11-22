"""结构化输出：连续调用 100 次，结果全部通过校验吗？

    python main.py prompt   # A：只靠提示词要 JSON；Pydantic 校验，不合格带着错误原因重试（最多 3 次）
    python main.py schema   # B：把 Pydantic 生成的 JSON Schema 交给服务端做约束解码
    python main.py both     # 两组都跑

A 走官方 anthropic SDK → 本机 llama-server 的 /v1/messages（它不认 output_config.format，只能靠提示词）；
B 走本机的 OpenAI 兼容接口 /v1/chat/completions 的 response_format（llama.cpp 用语法约束采样）。
连 Claude 时，B 的等价写法是 client.messages.parse(output_format=Triage) 或 output_config.format。
8 条告警轮流用，共 100 次；温度用服务端默认（0.8），让输出有变化。只用 anthropic + pydantic。
"""

import json
import os
import re
import sys
import time
import urllib.request
from collections import Counter
from typing import Literal

import anthropic
from pydantic import BaseModel, ValidationError

BASE = os.environ.get("LLAMA_URL", "http://127.0.0.1:8090")
client = anthropic.Anthropic(base_url=BASE, api_key="local-no-key")
N = int(os.environ.get("N", "100"))


class Triage(BaseModel):
    type: Literal["GC", "数据库", "支付", "前端"]
    level: Literal["P1", "P2", "P3"]
    cause: str
    first_check: str


ALERTS = [
    ("order-04 Pause Full 1873ms，接口 P99 升到 2.1s", "GC"),
    ("老年代使用率 97%，Young GC 每秒 3 次", "GC"),
    ("HikariPool-1 - Connection is not available, request timed out after 30000ms", "数据库"),
    ("慢查询：SELECT * FROM orders WHERE remark LIKE '%加急%' 耗时 8.2s", "数据库"),
    ("支付回调验签失败 38 次，订单停在待支付", "支付"),
    ("第三方支付网关 502，重试 3 次后放弃", "支付"),
    ("首页 main.8f3a.js 404，用户看到白屏", "前端"),
    ("Chrome 控制台报 Uncaught TypeError: Cannot read properties of undefined", "前端"),
]

SYSTEM = """你是值班告警分诊助手。根据 <alert> 里的告警，输出一个 JSON 对象，只输出 JSON，不要任何别的文字。
字段：type 只能是 GC、数据库、支付、前端 之一；level 只能是 P1、P2、P3 之一；cause 一句话写可能原因；first_check 一句话写先查什么。

<examples>
<alert>order-07 Full GC 每分钟 4 次，单次 1.2s</alert>
{"type": "GC", "level": "P1", "cause": "老年代被打满，频繁 Full GC", "first_check": "看 GC 日志和堆转储"}
<alert>库存服务获取数据库连接超时 30s</alert>
{"type": "数据库", "level": "P1", "cause": "连接池耗尽或慢 SQL 占住连接", "first_check": "看连接池活跃数和慢查询日志"}
</examples>"""


def classify_error(text: str, e: Exception) -> str:
    if not re.search(r"\{.*\}", text, re.S):
        return "没有 JSON"
    if isinstance(e, json.JSONDecodeError):
        return "JSON 语法错"
    if isinstance(e, ValidationError):
        err = e.errors()[0]
        return {"missing": "缺字段", "literal_error": "取值不在枚举里"}.get(err["type"], err["type"])
    return type(e).__name__


def parse(text: str) -> Triage:
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise ValueError("回答里没有 JSON 对象")
    return Triage.model_validate(json.loads(m.group(0)))


def run_prompt() -> None:
    first_ok = final_ok = right = calls = 0
    errs, tries = Counter(), Counter()
    t0 = time.time()
    for i in range(N):
        alert, want = ALERTS[i % len(ALERTS)]
        msgs = [{"role": "user", "content": f"<alert>{alert}</alert>"}]
        for attempt in (1, 2, 3):
            calls += 1
            r = client.messages.create(model="local", max_tokens=200, system=SYSTEM, messages=msgs)
            text = "".join(b.text for b in r.content if b.type == "text")
            try:
                t = parse(text)
                first_ok += attempt == 1
                final_ok += 1
                right += t.type == want
                tries[attempt] += 1
                break
            except (ValueError, json.JSONDecodeError, ValidationError) as e:
                errs[classify_error(text, e)] += 1
                msgs += [{"role": "assistant", "content": text},
                         {"role": "user", "content": f"上面的输出不合格（{classify_error(text, e)}：{str(e).splitlines()[0][:80]}）。"
                                                     "只输出符合要求的 JSON。"}]
        else:
            tries["3 次都失败"] += 1
    print(f"A 只靠提示词：{N} 次里 第一次就合格 {first_ok}，重试后合格 {final_ok}，类别正确 {right}；"
          f"一共调用 {calls} 次，用时 {time.time() - t0:.0f} s")
    print(f"   第几次合格：{dict(sorted(tries.items(), key=str))}")
    print(f"   失败原因：{dict(errs.most_common())}")


def run_schema() -> None:
    schema = Triage.model_json_schema()
    ok = right = 0
    errs = Counter()
    t0 = time.time()
    for i in range(N):
        alert, want = ALERTS[i % len(ALERTS)]
        body = {"messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": f"<alert>{alert}</alert>"}],
                "max_tokens": 200, "response_format": {"type": "json_schema", "json_schema": {"name": "triage", "schema": schema}}}
        req = urllib.request.Request(BASE + "/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=300) as r:
            text = json.load(r)["choices"][0]["message"]["content"]
        try:
            t = Triage.model_validate_json(text)
            ok += 1
            right += t.type == want
        except ValidationError as e:
            errs[classify_error(text, e)] += 1
    print(f"B JSON Schema 约束：{N} 次里 第一次就合格 {ok}，类别正确 {right}；用时 {time.time() - t0:.0f} s")
    if errs:
        print(f"   失败原因：{dict(errs)}")


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else "both"
    if arg in ("prompt", "both"):
        run_prompt()
    if arg in ("schema", "both"):
        run_schema()
