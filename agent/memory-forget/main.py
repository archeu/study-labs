"""⑤ 套进场景：同样 90 天的记忆，四套遗忘策略，2026-11-09 凌晨看结果。不需要模型。

    uv run python memory-forget/main.py
另外把 3×2×3 种策略组合的结果写进 grid.json（页面实验台用的就是它）。
"""

import itertools
import json
from pathlib import Path

from forget import generate, recall, report, run

POLICIES = {
    "什么都不忘": dict(episodic="keep", temp_ttl=False, pii="none"),
    "一刀切：30 天全删": dict(episodic="ttl30", temp_ttl=True, pii="write"),
    "只做写入时脱敏 + TTL": dict(episodic="keep", temp_ttl=True, pii="write"),
    "本页做法": dict(episodic="decay", temp_ttl=True, pii="write+night"),
}


def main():
    rows = generate()
    trade = sum(1 for _, r in rows if r["team"] == "trade")
    phones = sum(1 for _, r in rows if "用户 1" in r["text"])
    print(f"90 天共写入 {len(rows)} 条（交易组 {trade} 条，其中 {phones} 条日志片段带手机号）\n")
    print(f"{'策略':<16}{'活跃':>5}{'归档':>5}{'P0在':>5}{'规矩在':>6}{'过期临时':>8}{'手机号残留':>8}{'低价值旧病历':>10}")
    for name, p in POLICIES.items():
        r = report(run(p))
        print(f"{name:<16}{r['active']:>5}{r['archived']:>5}{'是' if r['p0_kept'] else '否':>5}{'是' if r['rule_kept'] else '否':>6}"
              f"{r['temp_left']:>8}{r['phone_left']:>8}{r['low_old']:>10}")

    db = run(POLICIES["本页做法"])
    leak = [t for t, *_ in recall(db, "trade", enforce_team=False) if t != "trade"]
    print(f"\n召回时漏了 team 条件：交易组的人能看到 {len(leak)} 条别的团队的记忆；带上条件：{sum(1 for t, *_ in recall(db, 'trade') if t != 'trade')} 条")

    grid = {}
    for e, t, p in itertools.product(["keep", "ttl30", "decay"], [False, True], ["none", "write", "write+night"]):
        grid[f"{e}|{int(t)}|{p}"] = report(run(dict(episodic=e, temp_ttl=t, pii=p)))
    Path(__file__).with_name("grid.json").write_text(json.dumps(grid, ensure_ascii=False))


if __name__ == "__main__":
    main()
