-- 用户 1024 的订单 9527：2 件 88 号机械键盘，共 798.00 元，待付款
INSERT INTO orders VALUES (9527, 1024, 79800, 'CREATED');
-- 9529：金额对不上，用来看回滚
INSERT INTO orders VALUES (9529, 1024, 12900, 'CREATED');
