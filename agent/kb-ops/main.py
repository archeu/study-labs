"""知识库运维的一天：按时间顺序往库目录里放文件、改文件、挪文件、删文件，每一步跑一次 sync，看它做了多少事。

需要 8091（Embedding）和 pgvector 容器（见 pano-06-runbook-to-report/README.md）。
uv run python -W ignore kb-ops/main.py      # 本机约 4 分钟，写 ops.json
"""
import json
import shutil
import sys
import time
from pathlib import Path

import pymupdf

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "pano-06-runbook-to-report"))
import corpus  # noqa: E402
import kb  # noqa: E402
import kb_ops  # noqa: E402

SRC = kb.DATA
ROOT = HERE / "data" / "kb"          # 库目录：<团队>/<文件>
GEN = HERE / "data" / "gen"          # 这一页额外造的文件

INVENTORY = [
    """<h1>inventory-svc 运维手册</h1><p>版本 v1.0 · 供应链组 · 2026-11-10 修订</p>
<h2>1 服务概览</h2><p>inventory-svc 负责扣库存和库存查询，被 order-svc 在下单时同步调用。K8s 命名空间 supply，4 个 Pod。</p>""",
    """<h2>2 发布与回滚</h2><p>先发 1 个 Pod，观察 10 分钟。发布后 20 分钟内扣库存失败率 &gt; 0.5%，立即回滚：</p>
<p>kubectl rollout undo deploy/inventory-svc -n supply</p><p>回滚后要核对库存流水表 stock_log，确认没有重复扣减。</p>""",
]


def make_inventory(out):
    doc = pymupdf.open()
    for html in INVENTORY:
        pg = doc.new_page()
        pg.insert_htmlbox(pymupdf.Rect(60, 64, 540, 790), html, css=corpus.CSS)
    doc.subset_fonts()
    doc.save(out, garbage=4, deflate=True)


def make_v32_typo(out):
    """手册 v3.2 改一处：§5.2 GC 日志从“保留 7 天”改成“保留 14 天”，版本号不变。"""
    old = corpus.JVM2
    corpus.JVM2 = old.replace("保留 7 天", "保留 14 天")
    try:
        corpus.make_runbook("v3.2", out)
    finally:
        corpus.JVM2 = old


def put(src, team, name=None):
    d = ROOT / team
    d.mkdir(parents=True, exist_ok=True)
    shutil.copy(src, d / (name or Path(src).name))


def main():
    shutil.rmtree(HERE / "data", ignore_errors=True)
    GEN.mkdir(parents=True)
    make_inventory(GEN / "inventory-svc-runbook-v1.0.pdf")
    make_v32_typo(GEN / "order-svc-runbook-v3.2.pdf")
    conn = kb_ops.connect()
    conn.execute("TRUNCATE ops_docs, ops_chunks")
    steps = []

    def step(name, desc, checks=()):
        print(f"\n== {name}：{desc}")
        st = kb_ops.sync(conn, ROOT)
        res = {"name": name, "desc": desc, "stat": st, "counts": kb_ops.counts(conn), "checks": []}
        print(f"  {st}  库里：{res['counts']}")
        for who, teams, q in checks:
            t0 = time.perf_counter()
            hits = kb_ops.search(conn, q, teams)
            ms = round((time.perf_counter() - t0) * 1000)
            top = [{"key": h[0], "version": h[1], "team": h[2], "section": h[3].split(" › ")[-1], "text": h[4], "cos": round(h[5], 3)} for h in hits]
            res["checks"].append({"who": who, "q": q, "top": top, "ms": ms})
            print(f"  [{who}] {q}")
            for t in top:
                print(f"      {t['cos']:.3f} {t['key']}@{t['version']}（{t['team']}）{t['section']}：{t['text'][:36]}")
        steps.append(res)

    trade = ("交易组", ["trade"])
    supply = ("供应链组", ["supply"])

    # 11-09 晚上：库里有旧版手册、11-08 的故障单、三篇笔记
    put(SRC / "runbook/order-svc-runbook-v3.1.pdf", "trade")
    put(SRC / "tickets/INC-20261108-01.docx", "trade")
    for n in ("caffeine-local-cache.md", "g1-old-gen.md", "hikari-pool.md"):
        put(SRC / "notes" / n, "public")
    step("首次入库", "手册 v3.1、11-08 故障单、3 篇笔记，全部从零处理",
         [(*trade, "order-04 接口的 P99 超过多少会告警？")])

    put(SRC / "runbook/order-svc-runbook-v3.2.pdf", "trade")
    put(SRC / "tickets/INC-20261109-02.docx", "trade")
    step("09:30 上传新版", "放进手册 v3.2 和 11-09 故障单；v3.1 文件还在目录里",
         [(*trade, "order-04 接口的 P99 超过多少会告警？")])

    step("再跑一次", "什么都没变")

    shutil.copy(GEN / "order-svc-runbook-v3.2.pdf", ROOT / "trade" / "order-svc-runbook-v3.2.pdf")
    step("改一处", "手册 v3.2 原地修订：GC 日志从保留 7 天改成 14 天，版本号不变",
         [(*trade, "GC 日志写在哪，保留几天？")])

    put(GEN / "inventory-svc-runbook-v1.0.pdf", "supply")
    step("加一份新手册", "供应链组上传 inventory-svc 手册 v1.0，不重建索引",
         [(*trade, "inventory-svc 怎么回滚？"), (*supply, "inventory-svc 怎么回滚？")])

    shutil.move(str(ROOT / "supply" / "inventory-svc-runbook-v1.0.pdf"), str(ROOT / "public" / "inventory-svc-runbook-v1.0.pdf"))
    step("改权限", "inventory-svc 手册从 supply 挪到 public，内容没变",
         [(*trade, "inventory-svc 怎么回滚？")])

    # 旧版 v3.1 的文件被人改了个错字（版本号没变）：不能因为它“最后被处理”就把 v3.2 挤下线
    corpus.make_runbook("v3.1", GEN / "order-svc-runbook-v3.1.pdf")
    import pymupdf as _p
    _d = _p.open(GEN / "order-svc-runbook-v3.1.pdf"); _d.set_metadata({"title": "order-svc 运维手册 v3.1（修订错字）"})
    _d.save(ROOT / "trade" / "order-svc-runbook-v3.1.pdf")
    step("旧版改错字", "v3.1 的文件被改了一下（内容哈希变了），v3.2 应该仍是现行版",
         [(*trade, "order-04 接口的 P99 超过多少会告警？")])

    (ROOT / "public" / "hikari-pool.md").unlink()
    step("删一篇", "删掉笔记 hikari-pool.md",
         [(*trade, "连接池等待线程告警，能不能直接把连接池调大？")])

    # 对照：同样的最终状态，从零全量重建要多久
    conn.execute("TRUNCATE ops_docs, ops_chunks")
    t0 = time.perf_counter()
    full = kb_ops.sync(conn, ROOT, log=lambda *_: None)
    full["seconds"] = round(time.perf_counter() - t0, 1)
    print(f"\n== 对照：最终状态从零全量重建：{full}")
    with conn.cursor() as cur:
        cur.execute("SELECT embed_model, count(*) FROM ops_chunks GROUP BY 1")
        models = dict(cur.fetchall())
    (HERE / "ops.json").write_text(json.dumps({"steps": steps, "full_rebuild": full, "embed_models": models},
                                              ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
