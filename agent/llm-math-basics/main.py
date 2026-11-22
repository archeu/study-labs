"""够用的数学：向量、点积和概率。只用标准库，不连模型。

    python main.py

依次手算：点积和余弦、矩阵乘向量、softmax（以及不减最大值时的溢出）、
很多个概率连乘的下溢和改用 log 相加、一次迷你“注意力”（点积 → softmax → 加权平均）。
"""

import math

# 2 维的玩具向量：第 1 维像“和 GC 有多相关”，第 2 维像“和订单业务有多相关”
VEC = {
    "GC 停顿":   (0.9, 0.2),
    "Full GC":   (0.8, 0.3),
    "老年代回收": (0.85, 0.1),
    "下单失败":   (0.1, 0.9),
    "支付超时":   (-0.1, 0.8),
}


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def norm(a):
    return math.sqrt(dot(a, a))


def cos(a, b):
    return dot(a, b) / (norm(a) * norm(b))


def matvec(m, v):
    """矩阵乘向量 = 矩阵的每一行和向量做一次点积。"""
    return [dot(row, v) for row in m]


def softmax(z, safe=True):
    m = max(z) if safe else 0.0           # 先减最大值：数学上结果不变，但 exp 不会溢出
    e = [math.exp(x - m) for x in z]
    s = sum(e)
    return [x / s for x in e]


def main() -> None:
    q = VEC["GC 停顿"]
    print("1) 点积和余弦：拿“GC 停顿”去比其他几个")
    for k, v in VEC.items():
        print(f"   {k:<6} 点积 {dot(q, v):+.3f}   余弦 {cos(q, v):+.3f}")

    print("\n2) 矩阵乘向量：一行一个点积")
    W = [[1.0, 0.0], [0.0, 1.0], [0.7, 0.7]]
    print(f"   W × {q} = {[round(x, 3) for x in matvec(W, q)]}")
    print(f"   Qwen2.5-0.5B 每出一个 token 约做 {494_000_000 / 1e9:.2f}G 次乘加，"
          f"即约 {2 * 494_000_000 / 1e9:.1f}G 次浮点运算（每个参数一乘一加，常用估算口径）")

    print("\n3) softmax：分数 → 加起来是 1 的概率")
    z = [2.0, 1.0, 0.1]
    print(f"   分数 {z} → 概率 {[round(p, 3) for p in softmax(z)]}")
    big = [1000.0, 999.0, 990.0]
    print(f"   分数 {big} → 先减最大值 {[round(p, 4) for p in softmax(big)]}")
    try:
        softmax(big, safe=False)
    except OverflowError as e:
        print(f"   不减最大值直接 exp(1000)：OverflowError: {e}")

    print("\n4) 连乘的下溢：300 个 token，每个概率 0.05")
    prod = 1.0
    for _ in range(300):
        prod *= 0.05
    print(f"   直接连乘 = {prod}（下溢成 0，没法比较两句话谁更可能）")
    print(f"   改成 log 相加 = {300 * math.log(0.05):.1f}（API 返回的 logprob 就是这个）")

    print("\n5) 迷你注意力：“GC 停顿”这个位置该从哪几个 token 取信息")
    keys = ["Full GC", "老年代回收", "下单失败", "支付超时"]
    scores = [dot(q, VEC[k]) / math.sqrt(2) for k in keys]   # 除以 √维数，防止维数大时分数过大
    w = softmax(scores)
    for k, s, p in zip(keys, scores, w):
        print(f"   {k:<6} 分数 {s:+.3f}   权重 {p:.3f}")
    mixed = [sum(p * VEC[k][i] for k, p in zip(keys, w)) for i in range(2)]
    print(f"   加权平均后的新向量 = ({mixed[0]:.3f}, {mixed[1]:.3f})")


if __name__ == "__main__":
    main()
