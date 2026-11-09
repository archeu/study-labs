-- 100 个商品，88 号是机械键盘，单价 299，库存 50
INSERT INTO product SELECT x, '商品' || x, 10 + x FROM SYSTEM_RANGE(1, 100);
UPDATE product SET name = '机械键盘', price = 299.00 WHERE id = 88;
INSERT INTO stock SELECT x, 50 FROM SYSTEM_RANGE(1, 100);
