# 评测进 CI：改提示词要过门禁

对应页面：[评测进 CI：改提示词要过门禁](https://study.ainiya.de/agent/eval-ci-gate.html)

| 文件 | 是什么 |
|---|---|
| `gate.py` | 门禁：候选和基线逐条配对，按规则判红绿，写一份能贴进 PR 的 Markdown 报告；红了退出码 1 |
| `eval-ci.yml` | GitHub Actions 示例：按路径触发、版本核对、基线按指纹缓存、跑候选、门禁、贴 PR 评论。**没有放进 `.github/workflows`，不会自动运行** |
| `reports/` | 三次判决的报告 |

规则（写在 `gate.py` 开头）：不许新增违规调用（基线已有的另开缺陷单）；通过、没怪错、没编造数字、看过现场逐条配对，变坏 b 减变好 c 不超过 2√(b+c)；平均输出 token、平均耗时不超过基线 × 1.2；某一类净变坏 ≥ 2 条记警告。

## 怎么跑

```bash
cd agent
uv run python eval-ci-gate/gate.py base-@3-r1 pr57-@4-r1 --noise base-@3-r2
uv run python eval-ci-gate/gate.py base-@3-r1 base-@3-r2
uv run python eval-ci-gate/gate.py base-@3-r1 fix-@5-r1 --noise base-@3-r2
```

## 预期输出

2026-11-26/27，Qwen3-1.7B Q4_K_M（关思考，温度 0.8），各 50 条：

```
@4 vs @3：🔴  没怪错 变坏 11、变好 3（净 +8，噪声线 7.5）；输出 token ×1.29；耗时 ×1.28
@3 第二遍 vs 第一遍：🟢  但输出 token、耗时也差了 ×1.18——成本门槛 ×1.2 几乎贴着噪声
@5 vs @3：🟢  每一项都在噪声线以内（没有变坏，也没有变好）；输出 token ×1.12
```

第二遍 @3 跑到一半时，llama-server 默认的内存提示词缓存（`--cache-ram 8192`）把内存占满，推理变慢；被拖慢的两条删掉后在 `--cache-ram 0` 的服务上续跑（`run.py --resume`）。

## 练习

1. `cost_ratio` 改成“同一版两遍的比值 × 1.1”，重跑三个判决。
2. @4 再跑一遍，两遍合起来和基线两遍配对。
3. 挑 12 条冒烟组，只跑它们过门禁，看 @4 还拦不拦得住。
