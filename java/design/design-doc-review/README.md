# 写给挑错的人看：技术方案文档与评审

对应页面：[写给挑错的人看：技术方案文档与评审](https://study.ainiya.de/design/design-doc-review.html)

交易靶场“满减叠加券”这次改造的技术方案：周四早上的草稿、评审后的定稿、评审记录，外加一个检查方案结构的小程序。

| 路径 | 是什么 |
|---|---|
| `docs/design-doc-v1.md` | 草稿：方案主体写了，目标没法验收、非目标是“无”、放弃的方案只有结论、超时降级成原价下单、全量发布、待定问题是“无” |
| `docs/design-doc.md` | 评审后的 v2，八节，94 行 |
| `docs/review.md` | 评审记录：会前批注摘录、会上谈的 3 件事、结论和每条修改的负责人 |
| `DesignDocLint` | 检查八节是否齐全、目标有没有数字、非目标是否为空、放弃的方案有没有“输在”、风险表有没有“谁定的”、灰度 / 回滚 / 老数据三个小节、待定问题是不是带负责人和期限的表 |

三条 ADR 在 [`../tech-selection-adr/docs/adr`](../tech-selection-adr/docs/adr/)，算例在 [`../requirements-modeling`](../requirements-modeling/)。

## 怎么跑

在 `java/` 目录下：

```
mvn -q -pl design/design-doc-review compile exec:java \
  -Dexec.mainClass=labs.design.design_doc_review.DesignDocLint \
  -Dexec.args="design/design-doc-review/docs/design-doc-v1.md design/design-doc-review/docs/design-doc.md"
```

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16：

```
✗ design-doc-v1.md（50 行）
    - 目标里没有一个数：做完了拿什么判断算不算达到
    - 非目标是空的：这次明确不做什么，至少写一条
    - 放弃的方案没写输在哪：规则引擎，不合适。
    - 放弃的方案没写输在哪：新建 pricing-svc，太重。
    - 风险表缺“谁定的”一列：出事时怎么对待用户，是业务决定，要写明谁拍的板
    - 第 7 节缺“### 灰度”：先给谁用、看什么指标
    - 第 7 节缺“### 回滚”：出事了怎么回去、多久能回去
    - 第 7 节缺“### 老数据”：上线前已有的数据怎么办
    - 待定问题不是一张表，写的是“无”：评审前一个待定的都没有，多半是没列出来
✓ design-doc.md（94 行）
```

## 练习

1. 不看 v2，只看检查报出的 9 条，把 `design-doc-v1.md` 改到全过，再和 v2 对照。
2. 把 v1 当成同事发来的方案，写 5 条批注，标上必须、建议、问题，再和 `review.md` 比。
3. 给自己手头的一个真实改动写一页纸方案，用同样的骨架，跑一遍检查。
