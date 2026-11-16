# 让一个组都这样做：技术领导的规范、Code Review、带人

对应页面：[让一个组都这样做：技术领导的规范、Code Review、带人](https://study.ainiya.de/design/tech-lead-review-mentoring.html)

把团队对合并请求的五条约定写成检查，在评审人打开之前先跑一遍。

| 路径 | 是什么 |
|---|---|
| `prs/pr-101.txt` | 合格的：只搬一种券，测试没动，描述三节齐全 |
| `prs/pr-102.txt` | 搬和加混在一起，1418 行，改了特征测试的期望值，带着迁移脚本，描述缺两节 |
| `prs/pr-103.txt` | 标题是 refactor，却“顺便修了舍入”并改了 10 个期望值 |
| `PrCheck` | 读标题、描述和 `git diff --numstat` 格式的改动清单，检查：描述有背景 / 怎么验证 / 回滚；非测试代码不超过 400 行；refactor 不改测试、不夹带行为变化；feat 不改已有的特征测试；表结构变更单独提 |

## 怎么跑

在 `java/` 目录下：

```
mvn -q -pl design/tech-lead-review-mentoring compile exec:java \
  -Dexec.mainClass=labs.design.tech_lead_review_mentoring.PrCheck \
  -Dexec.args="design/tech-lead-review-mentoring/prs/pr-101.txt design/tech-lead-review-mentoring/prs/pr-102.txt design/tech-lead-review-mentoring/prs/pr-103.txt"
```

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16：

```
✓ pr-101  refactor: 把现金券搬成规则类
✗ pr-102  feat: 满减叠加 + 顺手把剩下的券都搬了
    - 描述里没有“怎么验证”
    - 描述里没有“回滚”
    - 非测试代码改了 1418 行，超过 400 行：评审的人看不完，拆开提
    - 改了特征测试 CalcPriceCharacterizationTest.java 的已有内容：老行为的标准答案不能跟着新功能改
    - 表结构变更和代码混在一起：迁移脚本单独提，先上线，再提用到新列的代码
✗ pr-103  refactor: 把折扣券搬成规则类
    - 标题是 refactor，却改了测试 CalcPriceCharacterizationTest.java：重构不改行为，测试就不该动
    - 标题是 refactor，描述里出现“顺便”：夹带了行为变化的话，单独提一个
```

## 练习

1. 把 pr-102 的 7 个文件分到 4 个 PR 里，各写一份完整的描述，让 `PrCheck` 全过。
2. 给三个 PR 各写至少两条评审意见，标上必须、建议、小事；给 pr-101 写一条具体的表扬。
3. 加一条你自己组里出过事的约定，写进 `PrCheck`，并写一句它防的是什么。
