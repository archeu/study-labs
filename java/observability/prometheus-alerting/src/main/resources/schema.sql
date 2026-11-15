CREATE TABLE IF NOT EXISTS orders (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    sku     INT    NOT NULL,
    qty     INT    NOT NULL
);
