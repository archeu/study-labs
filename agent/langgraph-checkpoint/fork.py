"""时间旅行：回到全景那条线程“三个子 agent 刚查完”的检查点，人工改一段结论，从那里分叉重跑汇总。

    uv run python pano-08-alert-to-ticket/main.py run          # 先跑全景，停在等批准
    uv run python langgraph-checkpoint/fork.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "pano-08-alert-to-ticket"))
import main as pano  # noqa: E402  全景的图和节点

from langgraph.checkpoint.sqlite import SqliteSaver  # noqa: E402

import time  # noqa: E402
pano.FLAKY, pano.CRASH, pano.T0 = set(), False, time.perf_counter()
pano.MODEL_SECONDS = {k: 0 for k in pano.MODEL_SECONDS}
THREAD = {"configurable": {"thread_id": "alert-7f3a"}}

with SqliteSaver.from_conn_string(str(pano.DATA / "checkpoints.db")) as saver:
    graph = pano.build(saver)
    hist = list(graph.get_state_history(THREAD))
    print("分叉前的历史（新的在前）：")
    for s in hist:
        print(f"  step {s.metadata['step']:>2}  …{s.config['configurable']['checkpoint_id'][-8:]}  next={list(s.next)}")

    # 找到“三个子 agent 都交回来了、下一步是 summarize”的那个检查点
    target = next(s for s in hist if s.next == ("summarize",))
    print(f"\n回到 step {target.metadata['step']}，那时 findings 有 {len(target.values['findings'])} 段")

    # 人工补一段结论：update_state 不改旧检查点，而是在它后面长出一个新的
    new_cfg = graph.update_state(target.config, {"findings": [
        {"agent": "人工", "summary": "DBA 补充：20:10 起 orders 表有慢查询，但 P99 和 Full GC 时间点对得上，不是主因"}]},
        as_node="investigate")                  # 当作 investigate 写的：下一步自然是 summarize
    print(f"update_state 生成新检查点 …{new_cfg['configurable']['checkpoint_id'][-8:]}，"
          f"next={list(graph.get_state(new_cfg).next)}，从这里接着跑：")
    out = graph.invoke(None, new_cfg)           # 从分叉点往下跑：summarize → approve（又停下）
    print(f"  汇总时 findings 有 {len(graph.get_state(new_cfg).values['findings'])} 段，"
          f"停在 {list(graph.get_state(THREAD).next)}")

    print("\n分叉后的历史（新的在前，parent 指回分叉点）：")
    for s in graph.get_state_history(THREAD):
        parent = (s.parent_config or {}).get("configurable", {}).get("checkpoint_id", "")
        print(f"  step {s.metadata['step']:>2}  …{s.config['configurable']['checkpoint_id'][-8:]}  "
              f"parent …{parent[-8:] or '（无）':<9} source={s.metadata['source']:<6} next={list(s.next)}")
