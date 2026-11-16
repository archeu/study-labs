# 商家 301 搜“键盘”：Elasticsearch 的倒排索引、分片、写入和查询

对应页面：[商家 301 搜“键盘”：Elasticsearch 的倒排索引、分片、写入和查询](https://study.ainiya.de/search/es-inverted-index-shards.html)

## 怎么跑

```bash
DOCKER="sudo docker" ./run.sh new all     # 起 ES 9.5.5 单节点（127.0.0.1:19200，无认证，堆 1 GB），跑全部实验
./run.sh again search                     # 容器留着，只重跑一段：setup load analyze search nrt deep mapping
./run.sh clean
```

`EsLab` 只用 JDK HttpClient 调 REST API（Jackson 读响应），每段实验在类注释里有说明。

## 预期输出

2 核 ARM 虚拟机，JDK 17，Elasticsearch 9.5.5：

```
load：bulk 写 100000 单，11.5 s；3 个分片 33231 / 33219 / 33550
analyze：standard [88, 号, 机, 械, 键, 盘, 青, 轴]；cjk [88, 号机, 机械, 械键, 键盘, 青轴]
search：商家 301 + 待发货 + 标题含“键盘”：took 28 ms，命中 213
        标题含“键盘”：cjk 41862 单；standard 58340 单（混进“键帽”“硬盘”）
nrt：默认 refresh 下写入后 484～915 ms 可见；refresh=wait_for 的写请求约 870 ms
     空闲 35 s 后写一单马上搜：3 次里 2 次第一次就搜到（搜索顺带触发 refresh）
deep：from=10000,size=10 → 400 Result window is too large；search_after 翻 12000 条 1070 ms
mapping：动态映射 status 成 text，term "WAIT_SHIP" → 0 条，status.keyword → 1 条
```

## 练习

1. 建 `orders_by_shop`，写入和查询都带 `?routing=商家号`，看 `_shards.total` 和各分片文档数。
2. `refresh_interval: -1` 再灌 10 万单，比较耗时；灌完不 refresh 直接搜能搜到几条。
3. 给 `orders` 加 `"dynamic": "strict"`，写一单多带一个字段，看报错。
4. 把 1000 单的状态改一遍，看 `docs.deleted`，再 `_forcemerge`。
