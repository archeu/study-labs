# 从需求到设计：拆专家 SOP、定成功指标

对应页面：[从需求到设计：拆专家 SOP、定成功指标](https://study.ainiya.de/agent/requirements-to-design.html)

| 文件 | 是什么 |
|---|---|
| `sop/slow-api-full-gc.md` | “接口变慢，怀疑 GC”的 SOP：触发、5 个步骤（目的、数据、判断、分支）、不做的事、成功指标表 |
| `main.py` | ① 核对 SOP 和工具清单（每步的数据来源都有工具、都写了判断规则、没有多余工具）② 按 SOP 的指标给一次运行的对话记录打分 |
| `runs/pano-2026-11-24.json` | 全景页那次真实运行（本机 Qwen3-1.7B，5 轮）的完整对话记录 |

## 怎么跑

不需要模型服务。

```bash
cd agent && uv sync
uv run python requirements-to-design/main.py                                  # 核对 SOP + 给 runs/ 里那次打分
uv run python requirements-to-design/main.py ../pano-04-triage-agent/transcript.json   # 给你自己跑的一次打分
```

## 预期输出

2026-11-24，Python 3.13：

```
1) 检查 SOP：sop/slow-api-full-gc.md
  ✓ 步骤 1：看报错、慢请求和最近的上线：用 search_logs
  ……
   0 个问题

2) 给一次运行打分：pano-2026-11-24.json
   轮数         5   目标 ≤ 6  ✓
   落空率        50%   目标 ≤ 0.2  ✕
   证据可追溯率     结论里没有引用任何数字   目标 ≥ 1.0  ✕（SOP 要求证据带数字）
   未经批准的影响操作  0   目标 ≤ 0  ✓
```

## 练习

先猜结果，再运行。

1. 照格式写“线程池打满”的 SOP，数据来源用一个还不存在的 `get_pool_metrics`，看脚本怎么报。
2. 让 `parse_gc_log` 直接按 SOP 阈值给出“GC 是 / 不是直接原因”，再跑全景页，看结论还会不会和数据矛盾。
3. 给 `score()` 加“落空后有没有换参数重查”。
