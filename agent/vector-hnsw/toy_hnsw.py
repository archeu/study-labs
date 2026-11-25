"""手推一遍 HNSW：18 个二维点、3 层，看一次查询怎么从最高层一路下到第 0 层。

和真实 HNSW 的差别（为了能画在纸上）：
- 用平面上的欧氏距离代替余弦距离；
- 每个点在第几层是手工指定的（真实实现按指数分布随机抽：越高层的点越少）；
- 每层的边直接连“这一层里最近的几个点”（真实实现是逐个插入，用 ef_construction 搜邻居，再用启发式挑出方向分散的 M 个）。
搜索过程和真实算法一样：上面几层只留 1 个候选贪心往下走，第 0 层留 ef 个候选做“最好优先”的扩展。
python3 vector-hnsw/toy_hnsw.py        # 打印搜索轨迹；--json 输出给页面用的数据
"""
import heapq
import json
import math
import sys

P = {  # 名字: (x, y, 最高层)
    "A": (40, 40, 0), "B": (95, 25, 1), "C": (150, 60, 0), "D": (210, 30, 0), "E": (260, 75, 2),
    "F": (30, 110, 0), "G": (85, 95, 0), "H": (130, 130, 1), "I": (190, 105, 0), "J": (245, 140, 0),
    "K": (55, 170, 2), "L": (110, 190, 0), "M": (165, 175, 0), "N": (215, 200, 1), "O": (270, 195, 0),
    "R": (25, 215, 0), "S": (140, 225, 0), "T": (235, 240, 1),
}
QUERY = (205, 165)
NEIGH = {2: 1, 1: 2, 0: 3}        # 每层每个点连最近的几个
ENTRY = "K"                        # 第 2 层的入口
EF = 4                             # 第 0 层的候选池大小（hnsw.ef_search）
TOPK = 2


def d(a, b):
    pa = P[a][:2] if isinstance(a, str) else a
    pb = P[b][:2] if isinstance(b, str) else b
    return math.dist(pa, pb)


def layer_nodes(l):
    return [n for n, (_, _, top) in P.items() if top >= l]


def build():
    g = {}
    for l in (2, 1, 0):
        nodes = layer_nodes(l)
        adj = {n: set() for n in nodes}
        for n in nodes:
            for m in sorted((x for x in nodes if x != n), key=lambda x: d(n, x))[:NEIGH[l]]:
                adj[n].add(m); adj[m].add(n)
        g[l] = {n: sorted(v) for n, v in adj.items()}
    return g


def search(g, q=QUERY, ef=EF, k=TOPK):
    steps, cur = [], ENTRY
    steps.append({"layer": 2, "kind": "entry", "cur": cur, "dist": round(d(cur, q), 1)})
    for l in (2, 1):                       # 上层：只留 1 个候选，贪心
        moved = True
        while moved:
            moved = False
            best = min(g[l][cur], key=lambda x: d(x, q))
            steps.append({"layer": l, "kind": "look", "cur": cur, "seen": g[l][cur], "best": best,
                          "dist": round(d(cur, q), 1), "best_dist": round(d(best, q), 1)})
            if d(best, q) < d(cur, q):
                cur, moved = best, True
        steps.append({"layer": l, "kind": "down", "cur": cur})
    # 第 0 层：最好优先搜索，结果池最多 ef 个
    visited = {cur}
    cand = [(d(cur, q), cur)]               # 小根堆：待扩展，近的先扩
    res = [(-d(cur, q), cur)]               # 大根堆：结果池，堆顶是池里最远的
    while cand:
        dc, c = heapq.heappop(cand)
        if dc > -res[0][0] and len(res) >= ef:
            steps.append({"layer": 0, "kind": "stop", "cur": c, "dist": round(dc, 1), "worst": round(-res[0][0], 1)})
            break
        new = [n for n in g[0][c] if n not in visited]
        for n in new:
            visited.add(n)
            dn = d(n, q)
            if len(res) < ef or dn < -res[0][0]:
                heapq.heappush(cand, (dn, n)); heapq.heappush(res, (-dn, n))
                if len(res) > ef:
                    heapq.heappop(res)
        steps.append({"layer": 0, "kind": "expand", "cur": c, "new": new,
                      "pool": [n for _, n in sorted((-x, n) for x, n in res)], "visited": len(visited)})
    top = [n for _, n in sorted((-x, n) for x, n in res)][:k]
    truth = sorted(P, key=lambda n: d(n, q))[:k]
    steps.append({"layer": 0, "kind": "result", "top": top, "truth": truth, "visited": len(visited)})
    return steps


def main():
    g = build()
    steps = search(g)
    if "--json" in sys.argv:
        print(json.dumps({"points": P, "query": QUERY, "graph": g, "steps": steps, "ef": EF}, ensure_ascii=False))
        return
    for l in (2, 1, 0):
        print(f"第 {l} 层：{len(g[l])} 个点，" + "，".join(f"{n}-{'/'.join(v)}" for n, v in g[l].items()))
    for s in steps:
        print(s)
    print("一共算了", steps[-1]["visited"], "个点的距离（全部 18 个）")


if __name__ == "__main__":
    main()
