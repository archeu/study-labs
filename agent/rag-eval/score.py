"""读 answers.json（main.py 的输出）和 labels.json（人工核对），算三组数，写 eval.json。

1. 检索：30 题前 1 / 3 / 5 片命中率、MRR
2. 生成（按人工标注）：忠实度、正确率；3 道库里没有答案的题有没有老实说“没有”
3. 判官和人工比：关键词覆盖（阈值可调）和小模型判官，各抓住了几个不忠实的回答、误报了几个
uv run python rag-eval/score.py
"""
import json
import math
from pathlib import Path

HERE = Path(__file__).resolve().parent
REFUSE = "资料里没有"


def lex_flag(a, theta=0.5):
    """关键词判官：说“没有”的不判；有句子没标出处，或被引资料覆盖不到 theta，就判不忠实。"""
    if a["answer"].strip().startswith(REFUSE):
        return False
    return a["lex_uncited"] > 0 or (a["lex_min_cover"] is not None and a["lex_min_cover"] < theta)


def confusion(pred, truth):
    tp = sum(p and t for p, t in zip(pred, truth)); fp = sum(p and not t for p, t in zip(pred, truth))
    fn = sum(t and not p for p, t in zip(pred, truth)); tn = sum(not p and not t for p, t in zip(pred, truth))
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "recall": round(tp / (tp + fn), 2) if tp + fn else None, "precision": round(tp / (tp + fp), 2) if tp + fp else None}


def ci95(p, n):
    se = math.sqrt(p * (1 - p) / n)
    return round(max(0, p - 1.96 * se), 2), round(min(1, p + 1.96 * se), 2)


def main():
    A = json.loads((HERE / "answers.json").read_text())
    L = {x["qid"]: x for x in json.loads((HERE / "labels.json").read_text())}
    ans = [a for a in A if a["qid"].startswith("Q")]
    noans = [a for a in A if a["qid"].startswith("N")]
    n = len(ans)
    out = {"n": n}
    hit = {k: sum(1 for a in ans if a["rank"] and a["rank"] <= k) / n for k in (1, 3, 5)}
    out["retrieval"] = {**{f"hit@{k}": round(v, 3) for k, v in hit.items()},
                        "mrr": round(sum(1 / a["rank"] for a in ans if a["rank"]) / n, 3),
                        "hit@3_ci95": ci95(hit[3], n), "hit@5_ci95": ci95(hit[5], n)}
    faithful = [L[a["qid"]]["faithful"] for a in A]
    correct = [L[a["qid"]]["correct"] for a in ans]
    out["generation"] = {
        "faithful": sum(faithful), "of": len(A), "faithful_rate": round(sum(faithful) / len(A), 3),
        "correct_yes": sum(c == "yes" for c in correct), "correct_partial": sum(c == "partial" for c in correct),
        "correct_no": sum(c == "no" for c in correct),
        "refused_ok": sum(L[a["qid"]]["faithful"] and a["answer"].startswith(REFUSE) for a in noans), "noans": len(noans)}
    out["generation"]["faithful_ci95"] = ci95(out["generation"]["faithful_rate"], len(A))
    # 检索对不对 × 回答对不对：错误该算在哪一段
    grid = {}
    for a in ans:
        r = "检索到了" if a["rank"] and a["rank"] <= 3 else "没检索到"
        c = L[a["qid"]]["correct"]
        grid.setdefault(r, {}).setdefault(c, []).append(a["qid"])
    out["grid"] = grid
    unf = [not f for f in faithful]
    out["judge_llm"] = confusion([a["judge"].get("supported") is False for a in A], unf)
    out["judge_lex"] = {str(t): confusion([lex_flag(a, t) for a in A], unf) for t in (0.3, 0.4, 0.5, 0.6, 0.7, 0.8)}
    out["rows"] = [{"qid": a["qid"], "q": a["q"], "answer": a["answer"], "rank": a["rank"], "cover": a["lex_min_cover"],
                    "uncited": a["lex_uncited"], "refused": a["answer"].startswith(REFUSE),
                    "judge": a["judge"].get("supported"), "faithful": L[a["qid"]]["faithful"],
                    "correct": L[a["qid"]].get("correct"), "note": L[a["qid"]]["note"]} for a in A]
    out["time"] = {k: round(sum(a[k] for a in A) / len(A), 1) for k in ("t_retrieve", "t_gen", "t_judge")}
    (HERE / "eval.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print("检索：", out["retrieval"])
    print("生成：", out["generation"])
    print("错在哪一段：", {r: {c: len(v) for c, v in d.items()} for r, d in grid.items()})
    print("小模型判官：", out["judge_llm"])
    for t, c in out["judge_lex"].items():
        print(f"关键词判官 阈值 {t}：", c)
    print("每题平均耗时（秒）：", out["time"])


if __name__ == "__main__":
    main()
