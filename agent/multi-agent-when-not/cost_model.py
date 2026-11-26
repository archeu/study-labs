"""拆不拆的估算模型：一个 agent 查 k 块材料 vs 主 agent + k 个子 agent。页面实验台用的是同一套公式（JS 版）。

    uv run python multi-agent-when-not/cost_model.py

口径（都是估算，不是实测）：
- 一个 agent：k 块材料、每块查 r 轮，共 n = k*r 轮工具调用 + 1 轮下结论。原文每轮多 R/r，后面每一轮都要把前面所有原文再读一遍。
- 多 agent：主 agent 分诊 1 次；每个子 agent 自己跑 r+1 次，只读自己那块；主 agent 汇总 1 次，只读 k 段结论。
- 工具裁短：工具只返回相关的几十行，每块原文最多 CUT token。
- 前后依赖：子 agent 只能一个接一个跑，后一个的任务书要带上前面所有结论。
- 提示缓存：同一个 agent 下一轮的提示以上一轮为前缀，重读的部分按 1/10 计（价格按 Anthropic 文档的缓存读取价；
  写缓存的 1.25 倍加价忽略不计）；耗时也按 1/10 算（估算）。
- 耗时：每次调用 2 秒 + 每 4000 个“要现算的”输入 token 1 秒；云端并行时子 agent 同时跑，本机只能一个一个算。
"""

B, BS, C, OUT_TOOL, OUT_FINAL, CUT = 600, 400, 150, 60, 300, 2000


def loop_cost(first: float, step: float, calls: int, cache: bool) -> tuple[float, float, float, float]:
    """一个 agent 自己的循环：第 i 次输入 = first + i*step。返回 (输入总量, 折算输入, 最大一次, 耗时)。"""
    ins = [first + i * step for i in range(calls)]
    eff = [x if (i == 0 or not cache) else step + 0.1 * (x - step) for i, x in enumerate(ins)]
    return sum(ins), sum(eff), max(ins), sum(2 + e / 4000 for e in eff)


def estimate(k: int, R: int, r: int, cut: bool = False, dependent: bool = False,
             parallel: bool = True, cache: bool = False) -> dict:
    R = min(R, CUT) if cut else R
    step = R / r
    s_in, s_eff, s_max, s_time = loop_cost(B, step, k * r + 1, cache)
    single = {"calls": k * r + 1, "in": s_in, "eff": s_eff, "max": s_max, "time": s_time}

    subs = [loop_cost(BS + (j * C if dependent else 0), step, r + 1, cache) for j in range(k)]
    triage, summary = B, B + k * C
    serial = dependent or not parallel
    sub_time = sum(x[3] for x in subs) if serial else max(x[3] for x in subs)
    multi = {"calls": 2 + k * (r + 1), "in": triage + summary + sum(x[0] for x in subs),
             "eff": triage + summary + sum(x[1] for x in subs), "max": summary, "sub_max": max(x[2] for x in subs),
             "time": (2 + triage / 4000) + (2 + summary / 4000) + sub_time}
    return {"single": single, "multi": multi}


if __name__ == "__main__":
    cases = [("玩具现场（oncall.py）", dict(k=3, R=150, r=1)),
             ("真实规模：每块 6.5 万 token", dict(k=3, R=65000, r=2)),
             ("真实规模 + 开提示缓存", dict(k=3, R=65000, r=2, cache=True)),
             ("真实规模 + 工具裁短到 2000", dict(k=3, R=65000, r=2, cut=True)),
             ("裁短 + 缓存", dict(k=3, R=65000, r=2, cut=True, cache=True)),
             ("真实规模，子任务前后依赖", dict(k=3, R=65000, r=2, dependent=True)),
             ("真实规模，本机串行算力", dict(k=3, R=65000, r=2, parallel=False))]
    print(f"{'场景':22}{'单:调用':>7}{'输入':>9}{'折算':>9}{'最大上下文':>10}{'耗时':>6} |{'多:调用':>7}{'输入':>9}{'折算':>9}{'主最大':>7}{'子最大':>8}{'耗时':>6}{'折算比':>7}")
    for name, kw in cases:
        e = estimate(**kw)
        s, m = e["single"], e["multi"]
        print(f"{name:22}{s['calls']:>7}{s['in']:>9.0f}{s['eff']:>9.0f}{s['max']:>10.0f}{s['time']:>5.0f}s |"
              f"{m['calls']:>7}{m['in']:>9.0f}{m['eff']:>9.0f}{m['max']:>7.0f}{m['sub_max']:>8.0f}{m['time']:>5.0f}s{m['eff'] / s['eff']:>7.2f}")
