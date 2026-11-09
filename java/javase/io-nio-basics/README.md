# IO 与 NIO 基础：流、Buffer、Channel

对应页面：[IO 与 NIO 基础](https://archeu.github.io/study-notes/javase/io-nio-basics.html)
（全景：一个订单对象的一生 · 第 1、11 站；网络 IO 模型页的下一圈）

- `Demo`：导出对账 CSV、三种字符集读同一份字节、10 万行商品导入文件流式读、ByteBuffer 三指针。
- `Copy`：同一个文件五种复制法（byte / buffered / array / files / transfer），配 strace 数系统调用。

## 怎么跑

JDK 17。

```bash
cd java
mvn -q -pl javase/io-nio-basics compile
C=javase/io-nio-basics/target/classes
java -cp $C labs.javase.io_nio_basics.Demo /tmp/io-demo

head -c 2097152 /dev/urandom > /tmp/src.bin
for m in byte buffered array files transfer; do java -cp $C labs.javase.io_nio_basics.Copy $m /tmp/src.bin /tmp/dst-$m.bin; done
strace -f -c -e trace=read,write,sendfile java -cp $C labs.javase.io_nio_basics.Copy array /tmp/src.bin /tmp/dst.bin
strace -f -c -e trace=read,write,sendfile java -cp $C labs.javase.io_nio_basics.Copy none     # 基线：只启动 JVM
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20、locale C.UTF-8：

```
GBK   读：9527,1024,鏈烘閿洏,578.00
只取前 4 个字节解码：机?（替换字符）
flip()   position= 0 limit= 5 capacity=16
compact() position= 1 limit=16 capacity=16
复制 2 MB（减去基线后的系统调用 / 耗时）：
  byte      read ~2,097,159  write ~2,097,165   2183 ms
  buffered  read 263         write 268            64 ms
  array     read 263         write 271           2.4 ms
  files     sendfile 2                           2.8 ms
  transfer  sendfile 1                           4.1 ms
```

## 练习

1. 给对账 CSV 开头加 `'﻿'`，用 `xxd` 确认开头是 `efbb bf`。
2. 循环 2000 次不关 `Files.lines`，看 `/proc/<pid>/fd`，再用 `ulimit -n 256` 复现 Too many open files。
3. 用 16 字节 ByteBuffer 把两次到达的“半条消息”拼出完整的行（compact）。
