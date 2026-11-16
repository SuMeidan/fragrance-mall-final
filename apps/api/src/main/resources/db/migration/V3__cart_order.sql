-- Available stock has no artificial one-million cap: cancellation must restore reserved units.
ALTER TABLE product DROP CHECK ck_product_stock;
ALTER TABLE product ADD CONSTRAINT ck_product_stock_nonnegative CHECK (stock >= 0);

CREATE TABLE cart_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_cart_owner_product (account_id,product_id),
  CONSTRAINT fk_cart_account FOREIGN KEY (account_id) REFERENCES account(id),
  CONSTRAINT fk_cart_product FOREIGN KEY (product_id) REFERENCES product(id),
  CONSTRAINT ck_cart_quantity CHECK (quantity BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mall_order (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_no CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  account_id BIGINT NOT NULL,
  checkout_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  checkout_address_id BIGINT NOT NULL COMMENT 'Original checkout request binding; snapshot survives address deletion',
  status SMALLINT NOT NULL DEFAULT 0,
  total_cents BIGINT NOT NULL,
  receiver VARCHAR(32) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  region VARCHAR(120) NOT NULL,
  address_detail VARCHAR(200) NOT NULL,
  carrier VARCHAR(40) NULL,
  tracking_no VARCHAR(64) NULL,
  close_reason VARCHAR(200) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  paid_at DATETIME(6) NULL,
  shipped_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  closed_at DATETIME(6) NULL,
  UNIQUE KEY uq_order_no (order_no),
  UNIQUE KEY uq_order_checkout (account_id,checkout_key),
  KEY ix_order_owner (account_id,id),
  KEY ix_order_expiry (status,expires_at,id),
  CONSTRAINT fk_order_account FOREIGN KEY (account_id) REFERENCES account(id),
  CONSTRAINT ck_order_status CHECK (status IN (-3,-2,-1,0,1,2,3,4)),
  CONSTRAINT ck_order_amount CHECK (total_cents BETWEEN 1 AND 10000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  spec VARCHAR(80) NOT NULL,
  unit VARCHAR(16) NOT NULL,
  image_path VARCHAR(240) NOT NULL,
  price_cents BIGINT NOT NULL,
  quantity INT NOT NULL,
  UNIQUE KEY uq_order_product (order_id,product_id),
  KEY ix_order_item_product (product_id,order_id),
  CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES mall_order(id),
  CONSTRAINT fk_item_product FOREIGN KEY (product_id) REFERENCES product(id),
  CONSTRAINT ck_item_price CHECK (price_cents BETWEEN 1 AND 100000000),
  CONSTRAINT ck_item_quantity CHECK (quantity BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
