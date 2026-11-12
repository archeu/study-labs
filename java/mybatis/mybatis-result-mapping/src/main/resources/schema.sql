-- 交易靶场：100 笔订单（9501～9600，9527 在里面），每笔 1～3 件明细；20 万条支付流水
CREATE TABLE orders (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(16), amount BIGINT);
CREATE TABLE order_item (id BIGINT PRIMARY KEY, order_id BIGINT, sku_id BIGINT, qty INT, price BIGINT);
CREATE INDEX idx_item_order ON order_item(order_id);
CREATE TABLE pay_flow (id BIGINT PRIMARY KEY, order_id BIGINT, amount BIGINT, channel VARCHAR(16), created_at TIMESTAMP);

INSERT INTO orders SELECT 9500 + X, 1000 + MOD(X, 50), 'CREATED', 0 FROM SYSTEM_RANGE(1, 100);
-- 第 k 笔订单有 MOD(k,3)+1 件：1、2、3 件轮着来
INSERT INTO order_item
  SELECT o.id * 10 + n.X, o.id, 88 + n.X, n.X, 39900
  FROM orders o JOIN SYSTEM_RANGE(1, 3) n ON n.X <= MOD(o.id, 3) + 1;
UPDATE orders o SET amount = (SELECT SUM(qty * price) FROM order_item i WHERE i.order_id = o.id);
INSERT INTO pay_flow SELECT X, 9501 + MOD(X, 100), 100 + MOD(X, 900), 'WECHAT', TIMESTAMP '2026-11-11 00:00:00' + X * INTERVAL '0.4' SECOND
  FROM SYSTEM_RANGE(1, 200000);
