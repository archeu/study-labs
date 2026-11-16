# 架构决策记录

一个决定一个文件，编号连续、不复用。已接受的不改；要推翻，新写一条，把旧的状态改成“已被 ADR-NNNN 取代”。

| 编号 | 决定 | 状态 |
|---|---|---|
| [0007](0007-pricing-as-module-in-coupon-svc.md) | 算价放进 coupon-svc 的独立模块，不新建服务 | 已接受 |
| [0008](0008-stacking-rules-as-strategy-classes.md) | 叠加规则用策略类加活动配置实现，不引入规则引擎 | 已接受 |
| [0009](0009-store-discount-share-snapshot.md) | 每行分摊在下单时存进订单，退款按快照算，不重算 | 已接受 |
