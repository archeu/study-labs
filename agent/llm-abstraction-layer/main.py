"""业务代码只有一行 layer.complete("triage", ...)；三份配置，跑出三种结果。

    python main.py

配置 A：分诊用本机 Qwen2.5-0.5B，走 Claude 的 Messages 格式（anthropic 适配器）
配置 B：同一个任务改用本机 Qwen3-0.6B，走 OpenAI 兼容格式（openai 适配器），业务代码不变
配置 C：主模型地址写错（模拟主服务挂了），自动切到备用；同时演示“这个模型不收 temperature”被过滤
"""

import json
import os

from llm_layer import LLMLayer

HERE = os.path.dirname(__file__)
SYSTEM = "你是值班告警分诊助手。只输出 JSON：{\"type\": \"GC|数据库|支付|前端\", \"level\": \"P1|P2|P3\"}"


def triage(layer: LLMLayer, alert: str) -> None:
    r = layer.complete("triage", system=SYSTEM, user=f"<alert>{alert}</alert>")   # ← 业务代码只有这一行
    print(f"   {r.provider:<9} {r.model:<26} {r.ms:>5} ms  用量 {r.usage}  回答 {r.text.strip()[:60]!r}")
    if r.dropped:
        print(f"   这个模型不支持、已过滤的参数：{r.dropped}")
    if r.fallback_from:
        print(f"   先失败、已跳过：{r.fallback_from}")


def main() -> None:
    alert = "order-04 Pause Full 1873ms，接口 P99 升到 2.1s"
    for name in ("config_a.json", "config_b.json", "config_c.json"):
        cfg_path = os.path.join(HERE, name)
        with open(cfg_path, encoding="utf-8") as f:
            print(f"== {name}：{json.load(f)['_note']}")
        triage(LLMLayer.from_file(cfg_path), alert)


if __name__ == "__main__":
    main()
