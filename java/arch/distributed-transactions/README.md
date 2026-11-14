# 一单写三个库：2PC/XA、TCC、Saga、本地消息表与 Seata

对应页面：[一单写三个库：2PC/XA、TCC、Saga、本地消息表与 Seata](https://archeu.github.io/study-notes/arch/distributed-transactions.html)

交易靶场 J09 全景“一次下单跨三个库”的第 4～10 站。`DtxLab` 连一个 MySQL 8.4，里面建 `order_db`、`inventory_db`、`coupon_db` 三个库；每个“服务”只用连自己库的连接，谁也不碰别人的库。订单 9527（2 件 sku 88 + 券 MINUS20）分四段跑：

- `local`：三个本地事务各提交各的，订单服务死在调券之前
- `window`：XA、TCC（带 fence）、Saga、本地消息表 × 两种情况。券服务 300 ms 才处理完，并发买家在第 50 ms 来买 1 件 sku 88：
  - hot：可售 50、券正常，这单会成功——买家要等多久
  - last2：只剩 2 件、券早被 9400 用了，这单注定失败——买家买到没有，最后三个库什么样
- `xacrash`：XA 协调者死在 PREPARE 之前 / 之后（`Connection.abort` 掐断连接，模拟 kill -9），`XA RECOVER` 看到几个分支、买家的锁等多久、换一个新连接怎么收尾
- `fence`：TCC 第 6 站 kill -9 以后，TC 超时回滚：库存 Cancel 被重发、券的 Cancel 比 Try 先到、迟到的 Try。旁边还有一张 9528 冻结着 2 件。不开 fence 和开 fence（照 Seata `tcc_fence_log` 的逻辑手写）各跑一遍

TCC 和 fence 是手写的迷你版，没有引入 Seata：目的是看清 Seata 替你做了哪几件事。fence 的状态值（1 tried、2 committed、3 rollbacked、4 suspended）和三段判断照 Seata 2.x 的 `SpringFenceHandler`。

## 怎么跑

需要 Docker（会拉 `mysql:8.4`，约 800 MB）。按 JDK 8 写法（`--release 8`）。

```bash
cd java/arch/distributed-transactions
./run.sh          # 起容器 → 四段全跑（约 15 秒）
./run.sh again    # 容器留着，只重跑
./run.sh clean    # 删容器（连同数据卷）
```

当前用户不在 docker 组里时：`DOCKER="sudo docker" ./run.sh`。IDE 里运行 `labs.arch.distributed_transactions.DtxLab`，参数 `all`（或某一段的名字），先自己起好容器。

## 预期输出

本机 2 核 ARM，JDK 17.0.21，Connector/J 9.7.0，Docker 里 MySQL 8.4.13。跑了三次，毫秒数会差十几毫秒，其余一样：

```
[local] 三个服务各开各的本地事务、各自提交；订单服务在第 6 站（调券）之前被 kill -9
  结果：订单 9527 WAIT_PAY | sku 88 可售/冻结/已售 48/0/2 | 券 MINUS20 UNUSED → -
  订单待支付、库存扣了，券却没用：用户付 20 元的差价从哪来？没有任何一方知道要回滚
[window] 热门款：可售 50，券正常 → 这单会成功；券服务 300 ms 才处理完；买家第 50 ms 来买 1 件 sku 88
  XA 两阶段提交       这单成功（365 ms）｜买家等了 302 ms，买到了｜订单 9527 WAIT_PAY | sku 88 可售/冻结/已售 47/0/3 | 券 MINUS20 USED → 9527
  TCC（带 fence）   这单成功（375 ms）｜买家等了   5 ms，买到了｜订单 9527 WAIT_PAY | sku 88 可售/冻结/已售 47/0/3 | 券 MINUS20 USED → 9527
  Saga（正向 + 补偿）  这单成功（326 ms）｜买家等了   3 ms，买到了｜订单 9527 WAIT_PAY | sku 88 可售/冻结/已售 47/0/3 | 券 MINUS20 USED → 9527
  本地消息表          这单成功（335 ms）｜买家等了   3 ms，买到了｜订单 9527 WAIT_PAY | sku 88 可售/冻结/已售 47/0/3 | 券 MINUS20 USED → 9527
[window] 最后 2 件：可售 2，券早被 9400 用了 → 这单注定失败；券服务 300 ms 才处理完；买家第 50 ms 来买 1 件 sku 88
  XA 两阶段提交       这单失败（323 ms）｜买家等了 271 ms，买到了｜订单 9527 没有 | sku 88 可售/冻结/已售 1/0/1 | 券 MINUS20 USED → 9400
  TCC（带 fence）   这单失败（343 ms）｜买家等了   0 ms，没货，被拒｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 2/0/0 | 券 MINUS20 USED → 9400
  Saga（正向 + 补偿）  这单失败（324 ms）｜买家等了   0 ms，没货，被拒｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 2/0/0 | 券 MINUS20 USED → 9400
  本地消息表          这单失败（401 ms）｜买家等了   0 ms，没货，被拒｜订单 9527 WAIT_PAY | sku 88 可售/冻结/已售 0/0/2 | 券 MINUS20 USED → 9400
[xacrash] XA：协调者（订单服务）在两个时刻被 kill -9（Connection.abort 掐断连接），买家随后来买 sku 88，锁等待上限 2 秒
  xa_detach_on_prepare = 1，版本 8.4.13
  A 死在 PREPARE 之前：XA RECOVER 列出 0 个分支｜买家等了 3 ms，买到了｜订单 9527 没有 | sku 88 可售/冻结/已售 49/0/1 | 券 MINUS20 UNUSED → -
  B 死在 PREPARE 之后：XA RECOVER 列出 3 个分支｜买家等了 2056 ms，报错 1205（Lock wait timeout exceeded; try restarting transaction）
    此刻读到的（读已提交）：订单 9527 没有 | sku 88 可售/冻结/已售 50/0/0 | 券 MINUS20 UNUSED → -
  协调者恢复后用新连接 XA ROLLBACK 三个分支：XA RECOVER 剩 0 个｜买家等了 3 ms，买到了｜订单 9527 没有 | sku 88 可售/冻结/已售 49/0/1 | 券 MINUS20 UNUSED → -
[fence] 背景：订单 9528 已经冻结了 sku 88 的 2 件（它的全局事务还在走）。9527：订单 Try、库存 Try 成功，
        券分支已登记、Try 请求还在路上，这时订单服务 kill -9。TC 等到超时（真实 60 秒，这里直接触发）开始回滚：
  —— 不开 fence：Try/Confirm/Cancel 就是三条 SQL ——
     订单 Try                 成功（改了 1 行）｜订单 9527 CREATING | sku 88 可售/冻结/已售 48/2/0 | 券 MINUS20 UNUSED → -
     库存 Try                 成功（改了 1 行）｜订单 9527 CREATING | sku 88 可售/冻结/已售 46/4/0 | 券 MINUS20 UNUSED → -
     ✕ kill -9 订单服务；TC 超时 → 给三个分支推 Cancel
     订单 Cancel              改了 1 行，返回成功｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 46/4/0 | 券 MINUS20 UNUSED → -
     库存 Cancel（第 1 次，应答丢了）  改了 1 行，返回成功｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 48/2/0 | 券 MINUS20 UNUSED → -
     库存 Cancel（TC 重发）       改了 1 行，返回成功｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 50/0/0 | 券 MINUS20 UNUSED → -
     券 Cancel（Try 还没到）      改了 0 行，返回成功｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 50/0/0 | 券 MINUS20 UNUSED → -
     券 Try（迟到）              成功（改了 1 行）｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 50/0/0 | 券 MINUS20 LOCKED → 9527
     9528 的库存 Confirm：失败（冻结量不够了）｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 50/0/0 | 券 MINUS20 LOCKED → 9527
  —— 开 fence（照 Seata tcc_fence_log） ——
     订单 Try                 成功｜订单 9527 CREATING | sku 88 可售/冻结/已售 48/2/0 | 券 MINUS20 UNUSED → -
     库存 Try                 成功｜订单 9527 CREATING | sku 88 可售/冻结/已售 46/4/0 | 券 MINUS20 UNUSED → -
     ✕ kill -9 订单服务；TC 超时 → 给三个分支推 Cancel
     订单 Cancel              成功：执行了业务｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 46/4/0 | 券 MINUS20 UNUSED → -
     库存 Cancel（第 1 次，应答丢了）  成功：执行了业务｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 48/2/0 | 券 MINUS20 UNUSED → -
     库存 Cancel（TC 重发）       成功：已经做过，直接返回（幂等）｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 48/2/0 | 券 MINUS20 UNUSED → -
     券 Cancel（Try 还没到）      成功：空回滚，记一行 suspended｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 48/2/0 | 券 MINUS20 UNUSED → -
     券 Try（迟到）              被拒：fence 已有记录，拒绝执行 Try（防悬挂）｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 48/2/0 | 券 MINUS20 UNUSED → -
     9528 的库存 Confirm：成功｜订单 9527 CANCELLED | sku 88 可售/冻结/已售 48/0/2 | 券 MINUS20 UNUSED → -
```

怎么读：

- `window` hot：XA 下买家要等到 XA COMMIT 才拿到 sku 88 的行锁（≈ 券服务的 300 ms）；其余三种只等本地事务，几毫秒。
- `window` last2：XA 下买家等完了**买到了**（这单回滚，库存从来没“少过”）；TCC、Saga、本地消息表下买家**被拒**——那 2 件被一单注定失败的订单占着。本地消息表最后对不上：订单待支付、库存扣了、券用不了，它没有回滚这条路。
- `xacrash` B：PREPARE 以后连接断了，三个分支照样在（MySQL 5.7.7 起），行锁也在，买家等 2 秒报 1205；读的人读到的是旧值。`xa_detach_on_prepare=ON`（8.0.29 起默认）所以新连接能 `XA ROLLBACK`。
- `fence`：不开 fence 时，重发的 Cancel 把 9528 冻结的 2 件也“退”了，9528 的 Confirm 失败；迟到的 Try 把券锁给一张已取消的单，没人再来解。开 fence 三件事都挡住了。

## 练习

先猜结果，再运行。

1. 把 `COUPON_MS` 改成 2000：`window` hot 里 XA 的买家会怎样？（提示：锁等待上限是 5 秒。改成 6000 呢？）
2. 在 `fence` 段把券的 Cancel 和迟到的 Try 换个顺序（Try 先到、Cancel 后到），不开 fence 和开 fence 各是什么结果？
3. 给 `outbox()` 加上“反向消息”：券消费失败进死信后，发一条 STOCK_RETURN 和 ORDER_CANCEL。它现在像哪一种做法了？
4. 在 `xacrash` B 里，把收尾从 `XA ROLLBACK` 改成 `XA COMMIT`，再查三个库——协调者凭什么决定提交还是回滚？
