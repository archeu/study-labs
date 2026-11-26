"""打开检查点库看一眼：全景那条链路跑到“等人批准”时，SQLite 里到底存了什么。

    uv run python pano-08-alert-to-ticket/main.py run --flaky notes     # 先跑全景，生成 data/checkpoints.db
    uv run python langgraph-checkpoint/inspect_db.py
"""

import sqlite3
from pathlib import Path

from langgraph.checkpoint.sqlite import SqliteSaver

DB = Path(__file__).parent.parent / "pano-08-alert-to-ticket" / "data" / "checkpoints.db"
THREAD = {"configurable": {"thread_id": "alert-7f3a"}}

con = sqlite3.connect(DB)
print("表：", [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")])

print("\ncheckpoints 表（一行 = 一个 superstep 结束时的快照）")
rows = con.execute("SELECT checkpoint_id, parent_checkpoint_id, length(checkpoint), json_extract(CAST(metadata AS TEXT), '$.step') "
                   "FROM checkpoints WHERE thread_id='alert-7f3a' ORDER BY checkpoint_id").fetchall()
for cid, parent, size, step in rows:
    print(f"  step {step:>2}  id …{cid[-8:]}  parent …{(parent or '')[-8:] or '（无）':<9}  {size:5} 字节")

print("\nwrites 表（一行 = 某个任务交出的一个键的改动）")
for cid, task, ch, size in con.execute(
        "SELECT checkpoint_id, task_id, channel, length(value) FROM writes WHERE thread_id='alert-7f3a' ORDER BY checkpoint_id, task_id, idx"):
    print(f"  挂在 …{cid[-8:]}  任务 …{task[-6:]}  {ch:<22} {size:5} 字节")

with SqliteSaver.from_conn_string(str(DB)) as saver:
    t = saver.get_tuple(THREAD)                # 最新的那个检查点
    cp = t.checkpoint
    print("\n最新检查点里有哪些字段：", list(cp.keys()))
    print("  channel_values 的键：", list(cp["channel_values"].keys()))
    print("  channel_versions（每个键最后一次被写时的版本号）：", {k: int(str(v).split(".")[0]) for k, v in cp["channel_versions"].items() if not k.startswith("branch")})
    print("  metadata           ：", {k: v for k, v in t.metadata.items() if k in ("source", "step")})
    print("  挂着的 pending writes：", [(w[1], str(w[2])[:60]) for w in t.pending_writes])
