"""提示词基本功：同样 8 条告警，4 版提示词，比 JSON 合格率和分类正确率。

    python main.py

V0 含糊：一句“分析一下”
V1 说清楚：字段、取值范围、只输出 JSON
V2 V1 + 一个示例（支付类）
V3 V1 + 四类各一个示例 + 用 <alert> 标签分隔材料
官方 anthropic SDK → 本机 llama-server（Qwen2.5-0.5B），温度 0。只看趋势：8 条太少，本机模型也很弱。
"""

import json
import os
import re

import anthropic

client = anthropic.Anthropic(base_url=os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090") or None,
                             api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"))
MODEL = os.environ.get("MODEL", "qwen2.5-0.5b")

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

RULES = """你是值班告警分诊助手。根据告警输出一个 JSON 对象，只输出 JSON，不要任何别的文字。
字段：type 只能是 GC、数据库、支付、前端 之一；level 只能是 P1、P2、P3 之一；cause 一句话写可能原因。"""

ONE_SHOT = RULES + """

示例：
告警：pay-02 回调成功率 62%
{"type": "支付", "level": "P1", "cause": "第三方支付回调验签失败"}"""

FOUR_SHOT = RULES + """
告警放在 <alert></alert> 里。下面每类各一个示例，示例只演示格式，类别要按告警内容判断。

<examples>
<alert>order-07 Full GC 每分钟 4 次，单次 1.2s</alert>
{"type": "GC", "level": "P1", "cause": "老年代被打满，频繁 Full GC"}
<alert>库存服务获取数据库连接超时 30s</alert>
{"type": "数据库", "level": "P1", "cause": "连接池耗尽或慢 SQL 占住连接"}
<alert>pay-02 回调成功率 62%</alert>
{"type": "支付", "level": "P1", "cause": "第三方支付回调验签失败"}
<alert>商品详情页 vendor.js 加载失败，按钮点不动</alert>
{"type": "前端", "level": "P2", "cause": "静态资源发布不完整"}
</examples>"""

VARIANTS = {
    "V0 含糊的一句话": ("", lambda a: f"分析一下这个告警：{a}"),
    "V1 说清字段和取值": (RULES, lambda a: f"告警：{a}"),
    "V2 再加一个示例": (ONE_SHOT, lambda a: f"告警：{a}"),
    "V3 四类示例+标签": (FOUR_SHOT, lambda a: f"<alert>{a}</alert>"),
}


def run(system: str, user: str) -> str:
    kw = {"system": system} if system else {}
    # anthropic SDK 2.x 的 create() 已经没有 temperature 参数（新 API 版本不收采样参数），
    # 本机 llama-server 还认，用 extra_body 塞进请求体；连 Claude 时去掉这一行
    r = client.messages.create(model=MODEL, max_tokens=120, extra_body={"temperature": 0},
                               messages=[{"role": "user", "content": user}], **kw)
    return "".join(b.text for b in r.content if b.type == "text")


def judge(text: str, want: str) -> tuple[bool, bool, str]:
    m = re.search(r"\{.*?\}", text, re.S)
    try:
        d = json.loads(m.group(0)) if m else None
    except json.JSONDecodeError:
        d = None
    ok_json = isinstance(d, dict) and {"type", "level", "cause"} <= d.keys() and d.get("level") in ("P1", "P2", "P3")
    got = d.get("type") if isinstance(d, dict) else "—"
    return ok_json, got == want, str(got)


def main() -> None:
    for name, (system, make) in VARIANTS.items():
        n_json = n_right = 0
        gots = []
        for alert, want in ALERTS:
            ok_json, right, got = judge(run(system, make(alert)), want)
            n_json += ok_json
            n_right += right
            gots.append(got)
        print(f"{name:<14} JSON 合格 {n_json}/8，类别正确 {n_right}/8   答的类别：{' '.join(gots)}")


if __name__ == "__main__":
    main()
