"""省钱：提示词缓存命中前后的延迟和成本（过关题 2）。

    python main.py warm      # 约 2000 token 的固定前缀 + 10 条不同告警：第 1 次冷，后 9 次命中
    python main.py break     # 前缀开头放请求号：每次都冷（只跑 3 次）
官方 anthropic SDK → 本机 llama-server（它自动做前缀缓存，usage 里有 cache_read_input_tokens）。
成本按 Claude 文档的价格换算（本机不收钱）；连 Claude 时要在请求里打开 cache_control，见页面。
"""

import os
import sys
import time

import anthropic

client = anthropic.Anthropic(base_url=os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090") or None,
                             api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"))
MODEL = os.environ.get("MODEL", "qwen2.5-0.5b")

# 美元 / 百万 token（Claude API 文档，2026-11）：输入、5 分钟缓存写入（1.25×）、缓存读取、输出
PRICE = {"Opus 5.5": (4.0, 5.0, 0.20, 20.0), "Sonnet 5.5": (2.0, 2.5, 0.10, 10.0), "Haiku 5.5": (0.10, 0.125, 0.01, 0.5)}

RUNBOOK = "\n".join(f"{i}. {t}" for i, t in enumerate([
    "Full GC 停顿超过 1 秒：先看 GC 日志里 Pause Full 前后的堆占用，再看是否有大对象分配或内存泄漏。",
    "老年代使用率持续高于 90%：dump 堆，看占用最大的对象类型；检查缓存是否设置了上限。",
    "Young GC 频率异常升高：看分配速率，排查突发流量或批量任务。",
    "数据库连接池耗尽：看活跃连接数和等待线程，排查慢 SQL 和未关闭的连接。",
    "慢查询：看执行计划，确认是否走索引，LIKE 前置通配符会导致全表扫描。",
    "支付回调失败：检查验签证书是否过期、回调地址是否可达、网关返回码。",
    "第三方网关 5xx：看对方状态页，确认是否需要切换备用通道。",
    "前端白屏：检查静态资源发布是否完整，CDN 缓存是否刷新。",
] * 8, 1))                                        # 重复几遍，把前缀撑到 2000 token 左右

SYSTEM = f"""你是值班告警分诊助手。根据 <alert> 输出 JSON：type（GC/数据库/支付/前端）、level（P1/P2/P3）、first_check。只输出 JSON。

<runbook>
{RUNBOOK}
</runbook>"""

ALERTS = ["order-04 Pause Full 1873ms", "老年代使用率 97%", "HikariPool 连接超时 30s", "慢查询 8.2s",
          "支付回调验签失败 38 次", "支付网关 502", "首页 main.js 404 白屏", "Young GC 每秒 3 次",
          "库存服务连接池耗尽", "商品页 vendor.js 加载失败"]


def ask(system: str, alert: str) -> dict:
    t0, first = time.perf_counter(), None
    with client.messages.stream(model=MODEL, max_tokens=40, system=system,
                                messages=[{"role": "user", "content": f"<alert>{alert}</alert>"}]) as s:
        for _ in s.text_stream:
            if first is None:
                first = time.perf_counter() - t0
        m = s.get_final_message()
    u = m.usage
    return {"ttft": first or 0, "fresh": u.input_tokens, "read": u.cache_read_input_tokens or 0, "out": u.output_tokens}


def cost(r: dict, model: str, cached: bool) -> float:
    """按实测的 usage 换算：开缓存时，读到的按读取价；没读到的前缀按写入价（1.25×，每次都写）。"""
    pin, pwrite, pread, pout = PRICE[model]
    if not cached:                                           # 没开缓存：全部按输入价
        return ((r["fresh"] + r["read"]) * pin + r["out"] * pout) / 1e6
    var = min(15, r["fresh"])                                # 粗略：最后 15 个 token 是这次的告警，不进缓存
    return (r["read"] * pread + (r["fresh"] - var) * pwrite + var * pin + r["out"] * pout) / 1e6


def run(make_system, n: int = 10) -> None:
    rows = [ask(make_system(i), a) for i, a in enumerate(ALERTS[:n])]
    for i, r in enumerate(rows, 1):
        print(f"第 {i:>2} 次：新算 {r['fresh']:>4}，缓存读 {r['read']:>4}，首字 {r['ttft']:5.2f}s")
    tot = sum(r["fresh"] + r["read"] for r in rows)
    print(f"{n} 次输入共 {tot} token，其中缓存读 {sum(r['read'] for r in rows)}")
    for model in PRICE:
        no = sum(cost(r, model, False) for r in rows)
        yes = sum(cost(r, model, True) for r in rows)
        print(f"   按 Claude {model} 价格：不开缓存 ${no:.4f}，开缓存 ${yes:.4f}（{'省' if yes < no else '反而多花'} {abs(1 - yes / no):.0%}）")


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else "warm"
    if arg == "warm":
        run(lambda i: SYSTEM)
    else:
        run(lambda i: f"[请求号 req-{time.time_ns()}]\n" + SYSTEM, n=3)   # 每次都要冷读，本机很慢，只跑 3 次
