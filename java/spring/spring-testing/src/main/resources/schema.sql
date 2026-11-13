-- 交易靶场：订单 + 付款流水。MySQL 和 H2 都能跑的写法
CREATE TABLE IF NOT EXISTS orders (
  id           BIGINT PRIMARY KEY,
  user_id      BIGINT      NOT NULL,
  amount_cents BIGINT      NOT NULL,
  status       VARCHAR(16) NOT NULL
);
CREATE TABLE IF NOT EXISTS pay_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id     BIGINT      NOT NULL,
  amount_cents BIGINT      NOT NULL,
  channel_txn  VARCHAR(32) NOT NULL,
  CONSTRAINT uk_channel_txn UNIQUE (channel_txn)
);
