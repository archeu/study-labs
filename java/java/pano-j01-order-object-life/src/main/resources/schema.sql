-- 交易靶场 J01 骨架：商品、库存、订单三张表
CREATE TABLE product (id BIGINT PRIMARY KEY, name VARCHAR(64), price DECIMAL(10, 2));
CREATE TABLE stock   (sku_id BIGINT PRIMARY KEY, qty INT NOT NULL);
CREATE TABLE orders  (id BIGINT PRIMARY KEY, user_id BIGINT, total DECIMAL(10, 2), status VARCHAR(16), created_at TIMESTAMP);
