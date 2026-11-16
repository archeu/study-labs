# 一句话里藏着 4 块钱：需求分析与业务建模

对应页面：[一句话里藏着 4 块钱：需求分析与业务建模](https://archeu.github.io/study-notes/design/requirements-modeling.html)

运营一句“满 300 减 40，要能和券叠加”，追问以后变成规则表和 7 个带数字的算例。这里把算例写成一张能跑的表，拿两种实现各跑一遍。

| 类 | 是什么 |
|---|---|
| `ExampleTable` | 7 个算例（E1～E7），每个写明对应的规则、购物车、问什么、期望答案；main 打印两种实现的结果 |
| `NaiveIfPricing` | “calcPrice 里加个 if”的写法：满减的 if 加在券分支后面，退款只认识券，没有每行分摊 |
| `RulePricing` | 按规则表写的：R1 先满减后用券、门槛看原价；R3 按行金额比例分摊，尾差归最大行；R4 退一件退行实付 |
| `Pricing` | 接口：应付、每行分摊、退一行退多少。金额用“分” |

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.design.requirements_modeling.ExampleTable` 的 main。
- 命令行：`cd java && mvn -q -pl design/requirements-modeling compile exec:java -Dexec.mainClass=labs.design.requirements_modeling.ExampleTable`

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16：

```
E1   已有       应付     360.00                 | 360.00  ✓ | 360.00  ✓   只用 9 折券
E2   活动       应付     360.00                 | 360.00  ✓ | 360.00  ✓   只有满减
E3   R1       应付     324.00                 | 320.00  ✗ | 324.00  ✓   满减 + 券（用户 1024 那单）
E4   R1       应付     243.90                 | 243.90  ✓ | 243.90  ✓   271 元，不满门槛 + 券
E5   R1       应付     234.00                 | 270.00  ✗ | 234.00  ✓   正好 300 元 + 券
E6   R3 R4    退 B    104.49                 | 116.10  ✗ | 104.49  ✓   E3 那单退保温杯
E7   R3       分摊     25.34 / 25.33 / 25.33  | 答不出   ✗ | 25.34 / 25.33 / 25.33  ✓   三行都是 133.33，尾差 1 分

加个 if：3 / 7 通过；按规则表：7 / 7 通过
```

（列宽有删减，内容一致。）

## 练习

先猜结果，再运行。

1. 把 `NaiveIfPricing.pay` 里两个 if 换个顺序，但门槛仍然用打完折的金额判断。E3 会过吗？E5 呢？
2. 给 R5（整单退才退券）和 R6（预算 50 万，烧完自动停）各写两个算例加进 `EXAMPLES`，再让 `RulePricing` 通过。
3. 运营改了 R2：券能叠两张。先在纸上列出受影响的规则和算例，再改代码，看和你列的一不一样。
