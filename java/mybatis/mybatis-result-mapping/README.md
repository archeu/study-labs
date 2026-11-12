# 100 笔订单发出 101 条 SQL：MyBatis 结果映射、N+1 与流式查询

对应页面：[100 笔订单发出 101 条 SQL](https://archeu.github.io/study-notes/mybatis/mybatis-result-mapping.html)（Java 地图 J05 · 全景《一个请求穿过 Spring》第 8、9 站）

交易靶场·查订单和明细、导出流水。不起 Web，H2 内存库（MySQL 模式）：100 笔订单（9501～9600），每笔 1～3 件明细，共 199 件；20 万条支付流水。

| 文件 | 作用 |
|---|---|
| `Lab` | 主程序：`nplus1` `lazy` `join` `auto` `limit` `handler` `stream` `pages`，不带参数全跑 |
| `OrderMapper` + `mapper/OrderMapper.xml` | 同一个“订单连明细”的 4 种 resultMap：嵌套查询（立即 / 懒加载）、JOIN 折叠、JOIN 但明细只写 `<id>`；流水的 List / Cursor / ResultHandler / 分页 |
| `OrderService` | 需要事务的几个场景：事务里懒加载、事务里 Cursor、不在事务里的 Cursor、分页导出 |
| `SqlCounter` | 挂在 `StatementHandler.prepare` 上的插件：数 SQL 条数和借连接次数（HikariCP 每借一次给一个新的代理对象） |
| `schema.sql` | 建表和造数 |

## 怎么跑

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl mybatis/mybatis-result-mapping compile exec:java \
  -Dexec.mainClass=labs.mybatis.mybatis_result_mapping.Lab
# 只跑某几个场景
mvn -q -pl mybatis/mybatis-result-mapping exec:java -Dexec.mainClass=labs.mybatis.mybatis_result_mapping.Lab -Dexec.args="nplus1 stream"
```

## 预期输出（本机 2 核 ARM，OpenJDK 17.0.21，Spring Boot 3.5.16，mybatis-spring-boot-starter 3.0.5 / MyBatis 3.5.19，H2）

| 场景 | 结果 |
|---|---|
| nplus1 嵌套查询（立即） | 100 笔 / 199 件，**SQL 101 条**，借连接 1 次，最快 8.9～10.9 ms |
| nplus1 JOIN | 100 笔 / 199 件，**SQL 1 条**，最快 1.7～2.1 ms |
| lazy 查完 | SQL 1 条，对象的类是 `Order_$$_jvst…`（Javassist 子类） |
| lazy 调 `getStatus()` | 不触发 |
| lazy 调 `toString()`（不碰 items） | 触发，+1 条 SQL |
| lazy 不在事务里碰 100 笔 items | SQL 101 条，**借连接 101 次** |
| lazy 在 `@Transactional` 里 | SQL 101 条，借连接 1 次 |
| lazy 交给 Jackson | `InvalidDefinitionException: No serializer found for class …EnhancedResultObjectProxyImpl`（引用链 `["handler"]`） |
| auto 明细只写 `<id>` | `Item#95271(sku=null, qty=null, price=null)`：PARTIAL 不自动映射嵌套结果 |
| limit JOIN 后 LIMIT 10 | 只拿到 6 笔，最后一笔 9506 只有 1 件（实际 3 件） |
| limit 先分订单页再 JOIN | 10 笔，件数都对 |
| handler 嵌套结果 + ResultHandler | `ExecutorException: Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler…` |
| stream selectList | 第 10 万条时堆多 34～43 MB（整个 List 都活着） |
| stream Cursor（事务里） / ResultHandler | 0.01～0.03 MB |
| stream Cursor（不在事务里） | `IllegalStateException: A Cursor is already closed.` |
| pages 分 10 页导出，不在事务里 | 循环结束时 0～0.9 MB |
| pages 同样的循环包在 `@Transactional` 里 | **34.3 MB**：每页都留在一级缓存里，事务结束才放 |

H2 内存库没有网络往返，耗时只看相对大小。Cursor 在 H2 上不占堆；换成 MySQL，Connector/J 默认会把整个结果集读进内存，要 `fetchSize="-2147483648"` 或 `useCursorFetch=true` 才真正流式（见页面 ⑥）。

## 练习

1. 把 `listOrdersNested` 改成“两步法”：先查订单，收集 id，再 `WHERE order_id IN (...)` 查明细，在 Java 里 `groupingBy` 装回去。预期 SQL 2 条。
2. 给 `scanOrdersJoin` 加上 `resultOrdered="true"`，handler 里每收到一个订单打印 id 和件数。预期不再报错，100 笔都完整。
3. 在 `application.properties` 里加 `mybatis.configuration.local-cache-scope=statement`，再跑 `pages`。预期事务里的堆也降到 1 MB 以下。再改回来，只在 `listFlowsPage` 上加 `flushCache="true"`：每次查之前清空一级缓存，但这一页查完还会放进去，预期剩下一页的量（约 3.4 MB）。
