"""一次调查的账本：按计费规则把每一轮的 token、钱和秒数算出来，再叠加几种省钱省时间的做法。

这是计算，不是实测：token 轨迹、单价、速度都是下面写明的假设（和全景页一致），
计费规则照 Claude API 的提示词缓存规则：命中的前缀按 0.1 倍算、不算 ITPM；新写进缓存的按 1.25 倍算、算 ITPM。

uv run python prod-cost-latency/ledger.py
"""
from dataclasses import dataclass, field

PRICE = {"main": (3.0, 15.0), "small": (1.0, 5.0)}   # 每百万 token 的美元：输入、输出（本页假设）
READ, WRITE = 0.1, 1.25                               # 缓存读、写的倍数
PREFIX, ALERT = 4000, 500                             # 系统提示 + 工具说明；告警本身
TOOLS = {"search_logs": 3000, "parse_gc_log": 1500, "thread_dump": 6000, "search_notes": 1000, "create_ticket": 200}
TRIMMED = {"search_logs": 1000, "parse_gc_log": 800, "thread_dump": 1500, "search_notes": 1000, "create_ticket": 200}
CALL_OUT, REPORT_OUT = 150, 600                       # 一次工具调用的输出；最后报告的输出
DECODE_TPS, PREFILL_TPS, BASE_S, TOOL_S = 50.0, 4000.0, 0.6, 2.0   # 生成 50 token/秒；没缓存的输入 4000 token/秒；固定 0.6 秒；一个工具 2 秒


@dataclass
class Opt:
    prefix_cache: bool = False
    history_cache: bool = False
    trim: bool = False
    parallel: bool = False
    triage: bool = False          # 先用小模型分诊一轮，TRIAGE_STOP 的事件到此为止
    stream: bool = False


TRIAGE_STOP = 0.4


@dataclass
class Bill:
    itpm: float = 0.0          # 计入 ITPM 的：没命中的输入 + 写缓存的
    read: float = 0.0
    out: float = 0.0
    usd: float = 0.0
    seconds: float = 0.0
    calls: int = 0
    ttft_report: float = 0.0
    rounds: list = field(default_factory=list)


def plan(o: Opt) -> list[list[str]]:
    """每一轮调哪些工具；最后一轮出报告（空列表）。"""
    if o.parallel:   # 前三个只读工具互不依赖：一轮里同时提议
        return [["search_logs", "parse_gc_log", "thread_dump"], ["search_notes"], ["create_ticket"], []]
    return [["search_logs"], ["parse_gc_log"], ["thread_dump"], ["search_notes"], ["create_ticket"], []]


def investigate(o: Opt, model: str = "main") -> Bill:
    b, sizes = Bill(), (TRIMMED if o.trim else TOOLS)
    pi, po = PRICE[model]
    history, cached_upto = 0, 0          # cached_upto：上一次请求写进缓存的前缀长度
    for tools in plan(o):
        total_in = PREFIX + ALERT + history
        if o.history_cache:
            read = PREFIX if cached_upto == 0 else cached_upto      # 第一轮只有别的调查留下的固定前缀
            write = total_in - read
            cached_upto = total_in
        elif o.prefix_cache:
            read, write = PREFIX, 0
        else:
            read, write = 0, 0
        fresh = total_in - read - write
        out = REPORT_OUT if not tools else CALL_OUT * len(tools)
        usd = (fresh * pi + write * pi * WRITE + read * pi * READ + out * po) / 1e6
        prefill = BASE_S + (fresh + write) / PREFILL_TPS
        gen = out / DECODE_TPS
        tool_s = (TOOL_S if tools else 0.0) if o.parallel else TOOL_S * len(tools)
        b.itpm += fresh + write; b.read += read; b.out += out; b.usd += usd; b.calls += 1
        b.seconds += prefill + gen + tool_s
        if not tools:
            b.ttft_report = prefill if o.stream else prefill + gen      # 不流式：整篇生成完才看到第一个字
        b.rounds.append({"in": total_in, "read": read, "write": write, "fresh": fresh, "out": out, "usd": usd})
        history += out + sum(sizes[t] for t in tools)
    return b


def per_incident(o: Opt) -> Bill:
    """一个事件的期望账单：先分诊的话，小模型一轮；40% 到此为止，其余再做完整调查。"""
    inv = investigate(o)
    if not o.triage:
        return inv
    t_in, t_out = PREFIX + ALERT, CALL_OUT
    pi, po = PRICE["small"]
    tri = Bill(itpm=t_in, out=t_out, usd=(t_in * pi + t_out * po) / 1e6,
               seconds=BASE_S + t_in / PREFILL_TPS + t_out / DECODE_TPS, calls=1)
    k = 1 - TRIAGE_STOP
    return Bill(itpm=tri.itpm + k * inv.itpm, read=k * inv.read, out=tri.out + k * inv.out, usd=tri.usd + k * inv.usd,
                seconds=tri.seconds + k * inv.seconds, calls=1 + int(round(k * inv.calls * 10)) / 10, ttft_report=inv.ttft_report)


def concurrency(b: Bill, itpm_budget=360_000, otpm_budget=72_000) -> tuple[int, str]:
    """主模型的额度能撑几个调查同时跑：每个调查每分钟用多少，按 ITPM 和 OTPM 取小的那个。
    只算主模型：小模型分诊用的是小模型自己那份额度（额度按模型类别给）。"""
    minutes = b.seconds / 60
    by_in = itpm_budget / (b.itpm / minutes)
    by_out = otpm_budget / (b.out / minutes)
    return (int(by_in), "ITPM") if by_in <= by_out else (int(by_out), "OTPM")


VARIANTS = [
    ("0 基线：不缓存、主模型、原样、串行", Opt()),
    ("1 + 前缀缓存", Opt(prefix_cache=True)),
    ("2 + 历史也缓存", Opt(prefix_cache=True, history_cache=True)),
    ("3 + 工具结果瘦身", Opt(prefix_cache=True, history_cache=True, trim=True)),
    ("4 + 只读工具并行", Opt(prefix_cache=True, history_cache=True, trim=True, parallel=True)),
    ("5 + 小模型先分诊", Opt(prefix_cache=True, history_cache=True, trim=True, parallel=True, triage=True)),
    ("6 + 报告流式", Opt(prefix_cache=True, history_cache=True, trim=True, parallel=True, triage=True, stream=True)),
]

if __name__ == "__main__":
    base = investigate(Opt())
    print("基线的 6 轮：")
    for i, r in enumerate(base.rounds, 1):
        print(f"  第 {i} 轮  输入 {r['in']:>6}  输出 {r['out']:>4}  ${r['usd']:.4f}")
    print(f"{'做法':<22}{'计入ITPM':>9}{'缓存读':>8}{'输出':>6}{'调用':>5}{'每个事件':>9}{'87个':>7}{'耗时':>7}{'报告首字':>8}{'并发上限':>11}{'每分钟做完':>7}")
    for name, o in VARIANTS:
        b = per_incident(o)
        inv = investigate(o)
        n, by = concurrency(inv)
        per_min = n / (inv.seconds / 60) / ((1 - TRIAGE_STOP) if o.triage else 1)   # 分诊挡掉的那些不占主模型
        print(f"{name:<22}{b.itpm:>9.0f}{b.read:>8.0f}{b.out:>6.0f}{b.calls:>5}{'$%.4f' % b.usd:>9}{'$%.2f' % (87 * b.usd):>7}"
              f"{b.seconds:>6.1f}s{b.ttft_report:>7.1f}s{n:>6}（{by}）{per_min:>8.1f}")
