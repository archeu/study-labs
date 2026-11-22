"""Embedding：拿一句值班提问去历史工单里找“相似故障”，按字重合 vs 按向量余弦。

    python main.py search    # 3 个提问 × 8 条工单：两种排序各取前 3
    python main.py traps     # 两个坑：否定句几乎一样近；查询加不加指令前缀
    python main.py dump      # 把相似度矩阵存成 JSON（页面实验台用）

连本机 llama-server 的向量模式：Qwen3-Embedding-0.6B（--embeddings --pooling last，端口 8092）。
向量已归一化，点积 = 余弦。只用标准库。
"""

import json
import os
import sys
import urllib.request

SERVER = os.environ.get("EMB_URL", "http://127.0.0.1:8092")
TICKETS = [
    "order-04 Full GC 停顿 1.8 秒，接口超时",
    "老年代回收太频繁，下单接口响应变慢",
    "堆内存打满触发 OOM，订单服务重启",
    "MySQL 连接池耗尽，下单请求排队",
    "慢 SQL 扫全表，订单列表查询超时",
    "支付回调超时，订单状态卡在待支付",
    "第三方支付网关返回 502",
    "发版后首页白屏，静态资源 404",
]
QUERIES = [
    "订单服务 20:15 变慢，疑似 GC 导致",
    "数据库连不上了，下单一直转圈",
    "用户付完钱订单还显示没付款",
]
INSTRUCT_ZH = "Instruct: 给定一条值班提问，找出描述同一类故障的历史工单\nQuery: "   # 自己写的中文指令
INSTRUCT_EN = "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "  # 模型说明里的默认模板


def embed(texts: list[str]) -> list[list[float]]:
    req = urllib.request.Request(SERVER + "/v1/embeddings", json.dumps({"input": texts}).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return [d["embedding"] for d in json.load(r)["data"]]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def overlap(a: str, b: str) -> float:
    """老办法：按字重合（去掉空格和标点后的字符集合的 Jaccard）。"""
    sa, sb = ({c for c in s if c.strip() and c not in "，。、 "} for s in (a, b))
    return len(sa & sb) / len(sa | sb)


def cmd_search(dump: bool = False) -> None:
    tv = embed(TICKETS)
    qv = embed(QUERIES)          # 主实验：查询不加前缀
    out = []
    for q, v in zip(QUERIES, qv):
        cos = [dot(v, t) for t in tv]
        ov = [overlap(q, t) for t in TICKETS]
        out.append({"q": q, "cos": [round(c, 3) for c in cos], "overlap": [round(o, 3) for o in ov]})
        if dump:
            continue
        print(f"\n提问：{q}")
        print("  按字重合 前 3：")
        for i in sorted(range(len(TICKETS)), key=lambda i: -ov[i])[:3]:
            print(f"     {ov[i]:.3f}  {TICKETS[i]}")
        print("  按向量余弦 前 3：")
        for i in sorted(range(len(TICKETS)), key=lambda i: -cos[i])[:3]:
            print(f"     {cos[i]:.3f}  {TICKETS[i]}")
    if dump:
        json.dump({"tickets": TICKETS, "rows": out}, sys.stdout, ensure_ascii=False)


def cmd_traps() -> None:
    a, b, c = embed(["订单变慢是 GC 导致的", "订单变慢不是 GC 导致的", "订单变慢是数据库导致的"])
    print("1) 否定句：")
    print(f"   “是 GC 导致的” vs “不是 GC 导致的”   余弦 {dot(a, b):.3f}")
    print(f"   “是 GC 导致的” vs “是数据库导致的”   余弦 {dot(a, c):.3f}")
    print("2) 查询端的指令前缀（Qwen3-Embedding 的说明建议查询端加，给了英文模板）：")
    tv = embed(TICKETS)
    for q in QUERIES:
        for name, text in (("不加    ", q), ("英文模板", INSTRUCT_EN + q), ("自写中文", INSTRUCT_ZH + q)):
            v = embed([text])[0]
            cos = sorted(((dot(v, t), i) for i, t in enumerate(tv)), reverse=True)
            gap = cos[0][0] - cos[1][0]
            print(f"   {name}：{q[:14]:<14} 第一名 {TICKETS[cos[0][1]][:16]:<16} {cos[0][0]:.3f}，领先第二名 {gap:.3f}")
    print(f"3) 一个向量 {len(tv[0])} 维 × 4 字节 = {len(tv[0]) * 4} 字节；100 万条工单约 {len(tv[0]) * 4 * 1e6 / 1e9:.1f} GB")


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else "search"
    {"search": cmd_search, "traps": cmd_traps, "dump": lambda: cmd_search(True)}[arg]()
