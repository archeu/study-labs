"""怎么选模型：在自己的题上量。8 条告警分 4 类，本机两个小模型各做一遍，比正确率和速度。

    python main.py

Qwen2.5-0.5B（8090）和 Qwen3-0.6B（8091，关掉思考），都用 OpenAI 兼容的 /v1/chat/completions，
温度 0，回答只取第一个出现的类别名。只用标准库。
"""

import json
import os
import re
import time
import urllib.request

MODELS = {
    "Qwen2.5-0.5B": os.environ.get("LLAMA_URL", "http://127.0.0.1:8090"),
    "Qwen3-0.6B（不思考）": os.environ.get("THINK_URL", "http://127.0.0.1:8091"),
}
LABELS = ["GC", "数据库", "支付", "前端"]
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
SYSTEM = "你是值班告警分类器。只回答一个类别：GC、数据库、支付、前端。不要解释。"


def classify(url: str, alert: str) -> tuple[str, float, int]:
    body = {"messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": alert}],
            "max_tokens": 16, "temperature": 0, "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(url + "/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=600) as r:
        d = json.load(r)
    text = d["choices"][0]["message"]["content"]
    pos = {lab: text.find(lab) for lab in LABELS if lab in text}
    got = min(pos, key=pos.get) if pos else text.strip()[:8]
    return got, time.time() - t0, d["usage"]["completion_tokens"]


RULES = [("GC", r"GC|老年代|Pause|堆"), ("数据库", r"SQL|SELECT|Hikari|连接池|Connection"),
         ("支付", r"支付|回调|网关"), ("前端", r"\.js|白屏|控制台|TypeError")]


def by_rules(alert: str) -> str:
    """基线：不用模型，几条正则。"""
    for lab, pat in RULES:
        if re.search(pat, alert):
            return lab
    return "?"


def main() -> None:
    ok = sum(by_rules(a) == w for a, w in ALERTS)
    print(f"===== 基线：4 条正则，不用模型 =====\n   正确 {ok}/{len(ALERTS)}，每条不到 1 毫秒\n")
    for name, url in MODELS.items():
        classify(url, ALERTS[0][0])                     # 预热：系统提示进缓存，计时更公平
        ok, secs = 0, []
        print(f"===== {name} =====")
        for alert, want in ALERTS:
            got, sec, n = classify(url, alert)
            ok += got == want
            secs.append(sec)
            print(f"   {'✓' if got == want else '✗'} 应为 {want:<3} 答 {got:<6} {sec:4.1f}s  {alert[:34]}")
        print(f"   正确 {ok}/{len(ALERTS)}，平均每条 {sum(secs) / len(secs):.1f} 秒\n")


if __name__ == "__main__":
    main()
