# ThreadLocal 与内存泄漏

对应页面：[ThreadLocal 与内存泄漏](https://archeu.github.io/study-notes/juc/threadlocal-leak.html)

| 类 | 看什么 |
|---|---|
| `Mixup` | 2 个 exec 线程，过滤器只在带 `X-User` 头时 set、不 remove：后面不带头的请求读到别人的用户号（串号）。参数 `remove` 打开 finally remove |
| `Leak` | 池里 1 个线程跑 N 个请求，每个往 ThreadLocal 里放 1 MB；用反射数线程的 ThreadLocalMap：活条目、key=null 的条目、还挂着多少 MB。模式 `static` / `perobj` / `remove` |

## 怎么跑

`--release 8` 编译，用 JDK 17 运行。`Leak` 要加 `--add-opens java.base/java.lang=ALL-UNNAMED`（反射读 `Thread.threadLocals`）。

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/threadlocal-leak compile
CP=juc/threadlocal-leak/target/classes
$J/bin/java -cp $CP labs.juc.threadlocal_leak.Mixup
$J/bin/java -cp $CP labs.juc.threadlocal_leak.Mixup remove
for m in static perobj remove; do
  $J/bin/java -Xmx512m --add-opens java.base/java.lang=ALL-UNNAMED -cp $CP labs.juc.threadlocal_leak.Leak $m 200
done
```

IDE 里运行 `Leak` 时，把 `--add-opens …` 加到 VM options。

## 预期输出

2 核 ARM，JDK 17.0.20，G1，`-Xmx512m`：

```
$ Mixup
请求 1  头 X-User=1024  线程 exec-1  下单用户=1024
请求 2  头 X-User=2048  线程 exec-2  下单用户=2048
请求 3  头 X-User=null  线程 exec-1  下单用户=1024  <-- 串号！
请求 4  头 X-User=null  线程 exec-2  下单用户=2048  <-- 串号！
…（5、6 同样串号；加 remove 参数后 3～6 都是 null）

$ Leak perobj 200
  [请求刚结束] 表长 128，活条目 22（22 MB），key=null 的条目 0（还挂着 0 MB）
  [Full GC 之后] 表长 128，活条目 2（0 MB），key=null 的条目 22（还挂着 22 MB）
  [再来一次 set 之后] 表长 128，活条目 2（0 MB），key=null 的条目 20（还挂着 20 MB）
```

- perobj 每次跑的数字不一样（实测 22、29 MB；1000 个请求 41 MB），取决于 GC 什么时候来、后面的 set 碰巧扫到哪几格。
- `static`：GC 后线程上挂着 1 MB（每次 set 覆盖同一格）；`remove`：0 MB，表一直是 16 格。
- “堆比开始时多占”约是挂着的 2 倍：1 MB 的数组放不进 1 MB 的 G1 region，按大对象占两个 region。

## 练习

先猜结果，再运行。

1. 把 `Mixup` 的 `if (header != null)` 去掉（入口无条件 set），不加 remove，还串号吗？留下了什么问题？
2. 把 `Leak` 的池子改成 8 个线程，用 `static` 模式跑 200 个请求，先预测 GC 后一共挂着多少 MB。
3. 写一个 `wrap(Runnable r)`：提交线程里读出 USER，执行前 set、finally 里 remove，用它把异步任务包一下。
