CREATE TABLE orders (
  id          BIGINT PRIMARY KEY,
  user_id     BIGINT      NOT NULL,
  amount_cents BIGINT     NOT NULL,
  status      VARCHAR(16) NOT NULL
);
