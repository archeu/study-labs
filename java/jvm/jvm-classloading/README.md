# 类加载与双亲委派：SPI 为什么要打破它

对应页面：[类加载与双亲委派：SPI 为什么要打破它](https://archeu.github.io/study-notes/jvm/jvm-classloading.html)（J03 全景第 1 站）

| 类 | 做什么 |
|---|---|
| `WhoLoads` | 打印加载器链（app → platform → null），以及 `String`、`DriverManager`、`Order` 各由谁定义；配 `-Xlog:class+load` 看 `Order` 在第一次 `new` 时才加载 |
| `TwoOrders` | 两个 `parent=null` 的 `URLClassLoader` 各加载一次同一个 `Order.class`：名字相同，`==` 为 false，强转报 `Order cannot be cast to Order` |
| `SpiDemo` | 两层加载器：`framework` 只认 `spi` 包（接口 `PaymentChannel`、框架代码 `ChannelRegistry`），`app` 能看到实现 `channel.MockPay`。框架用自己的加载器找不到实现，把 TCCL 换成 `app` 才找得到；换成不委派的 `isolated` 会报 `not a subtype` |
| `LoaderLeak` | 模拟反复热部署：每轮一个新加载器 + `Order` + 3 个代理类。`-Dkeep=true` 把对象留在 static 列表里 → `OutOfMemoryError: Metaspace`；`-Dkeep=false` 时加载器可回收，类被卸载 |

## 怎么跑

`--release 8` 编译，用 JDK 17 运行。

```bash
cd java && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl jvm/jvm-classloading compile
cd jvm/jvm-classloading
J=/usr/lib/jvm/java-17-openjdk-arm64/bin/java; M=labs.jvm.jvm_classloading

$J -cp target/classes $M.WhoLoads
$J -Xlog:class+load -cp target/classes $M.WhoLoads | grep -E "Order|DriverManager"
$J -cp target/classes $M.TwoOrders
$J -cp target/classes $M.SpiDemo
$J -XX:MaxMetaspaceSize=32m -Dkeep=true  -cp target/classes $M.LoaderLeak | grep -v static
$J -XX:MaxMetaspaceSize=32m -Dkeep=false -cp target/classes $M.LoaderLeak | grep -v static
```

`TwoOrders`、`SpiDemo`、`LoaderLeak` 按相对路径 `target/classes` 找类，要在模块目录里运行。

## 预期输出（2 核 ARM，JDK 17.0.20.1）

```
# WhoLoads
  String                     → null
  DriverManager              → jdk.internal.loader.ClassLoaders$PlatformClassLoader@…
  Order                      → jdk.internal.loader.ClassLoaders$AppClassLoader@…
[class,load] java.sql.DriverManager source: jrt:/java.sql
[class,load] labs.jvm.jvm_classloading.Order source: file:/…/target/classes/   ← 在“准备 new Order”之后

# TwoOrders
ca.getName().equals(cb.getName()) = true
ca == cb                           = false
ClassCastException: class labs.jvm.jvm_classloading.Order cannot be cast to class labs.jvm.jvm_classloading.Order
  (… Order is in unnamed module of loader java.net.URLClassLoader @251a69d7; … Order is in unnamed module of loader 'app')

# SpiDemo
① 框架用自己的加载器找实现：0 个 []
② TCCL = framework → 0 个 []
③ TCCL = app        → 1 个 [MockPay(loader=app)]
④ TCCL = isolated   → ServiceConfigurationError: …PaymentChannel: …MockPay not a subtype

# LoaderLeak -Dkeep=true
第 1500 轮  元空间已用 24.6 MB  已加载类 6970  已卸载类 0
Exception in thread "main" java.lang.OutOfMemoryError: Metaspace
# LoaderLeak -Dkeep=false
第 1500 轮  元空间已用 24.6 MB  已加载类 6970  已卸载类 0
第 2000 轮  元空间已用 8.5 MB  已加载类 2778  已卸载类 6192
跑完了，没有 OOM
```

## 练习

先猜结果，再运行。

1. 在 `TwoOrders` 里把两个加载器的 parent 从 `null` 改成 `TwoOrders.class.getClassLoader()`：`ca == cb` 会变吗？强转还会失败吗？
2. 在 `SpiDemo` 里新建一个线程池，在池里的线程上调用 `viaTccl()`，不手动设 TCCL：找得到几个实现？（线程的 TCCL 继承自创建它的线程）
3. `LoaderLeak -Dkeep=true` 时，只把 `HOLD.add(o)` 删掉、代理对象照留：还会 OOM 吗？为什么？
