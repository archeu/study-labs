# 锁过期时 A 还在查库：看门狗、fencing token 与 Lua 条件写

对应页面：[锁过期时 A 还在查库：看门狗、fencing token 与 Lua 条件写](https://archeu.github.io/study-notes/redis/redis-lock-expire-fencing.html)（Java 地图 J07 · 全景“一次商品详情查询”第 5、7 站）

交易靶场商品服务读 88 号：第 5 站抢重建锁 `SET NX PX 3000`，第 6 站查库，第 7 站回填。这里只看第 7 站那一下怎么写：
A 查得比锁还久，锁过期了，期间运营把 399 改成 299，A 回来还能不能把 399 写进缓存。数据库用一个可改的变量代替。

| 类 | 做什么 |
|---|---|
| `ProductCache` | ⑤ 的最小案例：`get(id)` 走 Redis → 抢锁 → 查库 → 回填。回填有四种写法（直接 SET / 先 GET 比 token 再 SET / Lua 锁守卫 / Lua 版本守卫），`invalidate` 用 DEL 或写墓碑 |
| `Lab` | 三段实验：`stale` / `dog` / `lua`；`holder` 是 dog 段起的持锁子进程，不要直接跑 |

## 怎么跑

需要 docker（本机不在 docker 组里就用 `DOCKER="sudo docker" ./run.sh`）。代码按 JDK 8 写（Lettuce 6.8.2、Redisson 4.8.0），`dog` 段用 `kill -STOP`，要 Linux 或 macOS。

```bash
./run.sh          # 起 Redis 8.10.3 容器（端口 63798），三段全跑
./run.sh stale    # 容器留着，只跑某一段（stale / dog / lua）
./run.sh clean    # 删掉容器
```

Redisson 4.8.0 带 Netty 4.2，Lettuce 6.8.2 带 Netty 4.1，混在一起会 `NoSuchMethodError`，所以 pom 里用 `netty-bom` 统一到 4.2.18。

| 段 | 做什么 | 对应页面 |
|---|---|---|
| stale | 三条时间线（T1 有人接手 / T2 没人接手 / T3 锁没过期）× 五种回填写法，缓存最后是几块钱 | ②、⑤、⑥ 实验台 |
| dog | Redisson：`lock(2, SECONDS)` 没有看门狗；`lock()` 的 PTTL 被续；持锁进程被 `kill -STOP` 后锁过期、醒来解锁失败 | ⑥ 第一圈 1、2 |
| lua | 脚本空转时别的客户端什么时候收到 BUSY，`SCRIPT KILL` 什么时候杀得掉；`SCRIPT FLUSH` 后 EVALSHA 报 NOSCRIPT | ⑥ 第一圈 5、7 |

## 预期输出（本机 2 核 ARM，JDK 17.0.21，Redis 8.10.3 同机容器）

```
== stale：88 号重建锁 PX 3000，A 查到 399 元（version 1）；第 1 秒后台把价格改成 299（version 2）并让缓存作废
-- T1 有人接手：A 第 2.9 s 还在锁里，第 4.0 s 发回填；B 第 3.3 s 来读
   直接 SET                             A 回填写入  B 拿到 299  查库 2 次  缓存：399         ✗ 旧价还要挂 30 分钟
   先 GET 锁比 token，再 SET               A 回填写入  B 拿到 299  查库 2 次  缓存：399         ✗ 旧价还要挂 30 分钟
   Lua：锁还是我的才写                        A 回填被拒  B 拿到 299  查库 2 次  缓存：299         ✓
   Lua：版本不比缓存旧才写，作废用 DEL              A 回填被拒  B 拿到 299  查库 2 次  缓存：299         ✓
   Lua：版本不比缓存旧才写，作废写墓碑                A 回填被拒  B 拿到 299  查库 2 次  缓存：299         ✓
-- T2 没人接手：A 第 2.9 s 还在锁里，第 4.0 s 发回填
   直接 SET                             A 回填写入    查库 1 次  缓存：399         ✗ 旧价还要挂 30 分钟
   先 GET 锁比 token，再 SET               A 回填写入    查库 1 次  缓存：399         ✗ 旧价还要挂 30 分钟
   Lua：锁还是我的才写                        A 回填被拒    查库 1 次  缓存：空          ✓ 下次读查库拿 299
   Lua：版本不比缓存旧才写，作废用 DEL              A 回填写入    查库 1 次  缓存：399         ✗ 旧价还要挂 30 分钟
   Lua：版本不比缓存旧才写，作废写墓碑                A 回填被拒    查库 1 次  缓存：墓碑 v2     ✓ 下次读查库拿 299
-- T3 锁没过期：A 第 1.5 s 还在锁里，第 2.0 s 发回填
   直接 SET                             A 回填写入    查库 1 次  缓存：399         ✗ 旧价还要挂 30 分钟
   先 GET 锁比 token，再 SET               A 回填写入    查库 1 次  缓存：399         ✗ 旧价还要挂 30 分钟
   Lua：锁还是我的才写                        A 回填写入    查库 1 次  缓存：399         ✗ 旧价还要挂 30 分钟
   Lua：版本不比缓存旧才写，作废用 DEL              A 回填写入    查库 1 次  缓存：399         ✗ 旧价还要挂 30 分钟
   Lua：版本不比缓存旧才写，作废写墓碑                A 回填被拒    查库 1 次  缓存：墓碑 v2     ✓ 下次读查库拿 299
== dog：Redisson 4.8.0，lockWatchdogTimeout 改成 3000 ms（默认 30000），续期间隔是它的 1/3
   lock(2, SECONDS) 后干 2.1 秒的活，PTTL： 0.0s:1998 1.0s:997 1.9s:97 2.1s:-2   还拿着锁吗：false
   lock() 不带 leaseTime，持锁 4 秒里每 250 ms 看一次 PTTL：最低 1997，最高 2994 ms（一直没到 0）
   kill -STOP 持锁进程：2052 ms 后锁过期；2053 ms 时另一个进程 tryLock = true；5 s 后 kill -CONT
   持锁进程醒来接着干完、解锁：UNLOCK_FAIL IllegalMonitorStateException: attempt to unlock lock, not locked by current thread by node id: … thread-id: 1
== lua：busy-reply-threshold = 5000 ms
   脚本只读、空转 6 s：第 1.0 s 发 PING，5.0 s 收到 BUSY Redis is busy running a script. You can only call SCRIPT KILL or SHUTDOWN NOSAVE.；第 5.5 s SCRIPT KILL → OK（5.5 s）；脚本 5.5 s 结束，ERR Script killed by user with SCRIPT KILL... script: 4b6ad8abc6157c95907ea557f9abbb0d7a3f…；之后 PING → PONG
   脚本先写了一个 key、空转 7 s：第 1.0 s 发 PING，5.0 s 收到 BUSY Redis is busy running a script. You can only call SCRIPT KILL or SHUTDOWN NOSAVE.；第 5.5 s SCRIPT KILL → UNKILLABLE Sorry the script already executed write commands against the dataset. You can e…（5.5 s）；脚本 7.0 s 结束，返回 1；之后 PING → PONG
   EVALSHA e9f69f2b… → 0；SCRIPT FLUSH（像重启、主从切换后的新主）之后再 EVALSHA → NOSCRIPT No matching script. Please use EVAL.
```

跑了两遍，stale 段 15 个格子结果相同；dog 段的毫秒数每次差几十毫秒。

## 练习

先猜结果，再运行。

1. 墓碑只存 2 秒：把 `Lab.stale` 里的 `10_000L` 改成 `2_000L`。“版本守卫 + 墓碑”在 T2、T3 还都是 ✓ 吗？
2. 看门狗调回默认 30 秒：把 `redissonConfig(3000)` 两处都改成 `30000`，跑 `dog`。持锁进程被停 5 秒，另一个进程还能拿到锁吗？
3. 脚本只空转 3 秒：把 `lua` 段只读脚本的 6000 改成 3000。第 1.0 s 发出的 PING 什么时候回来，回的是 BUSY 吗？
