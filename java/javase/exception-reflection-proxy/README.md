# 异常、反射、注解与动态代理：JDK 这一层

对应页面：[异常、反射、注解与动态代理](https://archeu.github.io/study-notes/javase/exception-reflection-proxy.html)
（全景：一个订单对象的一生 · 第 2、3、7、10 站；Spring AOP 两页的下一圈）

- `Model`：OrderService 接口、`@Audit`（RUNTIME）、`@BuildOnly`（CLASS）、受检 / 非受检两种异常、OrderServiceImpl。
- `TxAuditHandler`：手写的“事务 + 审计”InvocationHandler。
- `Demo`：5 段：try-with-resources 与 suppressed、异常的成本、反射 inflation 与强封装、注解保留策略、动态代理。

## 怎么跑

JDK 17。

```bash
cd java
mvn -q -pl javase/exception-reflection-proxy compile
mkdir -p /tmp/px && cd /tmp/px      # 生成的代理类会存到当前目录
java -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true \
  -cp ~/claude/study-labs/java/javase/exception-reflection-proxy/target/classes \
  labs.javase.exception_reflection_proxy.Demo
javap -c -p -cp . 'labs.javase.exception_reflection_proxy.$Proxy2'
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20（节选）：

```
主异常：库存不足：sku 88 要 100 件；被压住的：连接 B 关闭失败、连接 A 关闭失败
调用深度 100：new 一个带栈的异常 ~5800 ns，不填栈的 ~190 ns
同一处 NPE 连抛：第 115741 次起栈轨迹变成空的
第 1 次 invoke：调用方是 NativeMethodAccessorImpl.invoke0
第 17 次 invoke：调用方是 GeneratedMethodAccessor1.invoke
@BuildOnly（CLASS）：null；读到的注解对象的类：$Proxy1，是 Proxy 吗？true
代理类：$Proxy2，父类 java.lang.reflect.Proxy，final=true
用户 4040 风控超时：[代理] 捕获 RiskCheckTimeoutException → 受检异常，照样提交
拿类去做 JDK 代理 → IllegalArgumentException: OrderServiceImpl is not an interface
```

## 练习

先猜结果，再运行。

1. 给 `@Audit` 加 `rollbackFor`，让受检异常也回滚。
2. 给 handler 加 Method → 审计名的缓存，对比 100 万次代理调用的耗时。
3. 去掉 handler 里的 catch，让 InvocationTargetException 直接冒出去，调用方拿到什么？
4. 在 handler 里 `throw new IOException(cause)`，调用方拿到什么？
