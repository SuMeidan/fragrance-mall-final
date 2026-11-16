-- CNY integer cents; UTC success times; one whole-order payment/aftersale/refund.
CREATE TABLE payment (
  order_id BIGINT NOT NULL PRIMARY KEY,
  app_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seller_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  amount_cents BIGINT NULL,
  success_at DATETIME(6) NULL,
  issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  checked_at DATETIME(6) NULL,
  needs_review BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_payment_trade (trade_no),
  KEY ix_payment_success (status,success_at),
  KEY ix_payment_pending (status,checked_at,order_id),
  CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES mall_order(id),
  CONSTRAINT ck_payment_status CHECK (status IN ('PENDING','SUCCESS','CLOSED')),
  CONSTRAINT ck_payment_amount CHECK (amount_cents IS NULL OR amount_cents BETWEEN 1 AND 10000000000),
  CONSTRAINT ck_payment_receipt CHECK (status <> 'SUCCESS' OR (trade_no IS NOT NULL AND amount_cents IS NOT NULL AND success_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE after_sale (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REQUESTED',
  review_note VARCHAR(500) NOT NULL DEFAULT '',
  reviewed_by BIGINT NULL,
  reviewed_at DATETIME(6) NULL,
  return_carrier VARCHAR(40) NULL,
  return_tracking_no VARCHAR(64) NULL,
  received_at DATETIME(6) NULL,
  restocked BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_sale_order (order_id),
  KEY ix_sale_state (state,id),
  CONSTRAINT fk_sale_order FOREIGN KEY (order_id) REFERENCES mall_order(id),
  CONSTRAINT fk_sale_reviewer FOREIGN KEY (reviewed_by) REFERENCES account(id),
  CONSTRAINT ck_sale_state CHECK (state IN ('REQUESTED','REJECTED','RETURN_REQUIRED','REFUND_PENDING','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refund (
  after_sale_id BIGINT NOT NULL PRIMARY KEY,
  request_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  amount_cents BIGINT NOT NULL,
  success_at DATETIME(6) NULL,
  last_error_code VARCHAR(64) NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_refund_request (request_no),
  KEY ix_refund_success (status,success_at),
  KEY ix_refund_pending (status,updated_at,after_sale_id),
  CONSTRAINT fk_refund_sale FOREIGN KEY (after_sale_id) REFERENCES after_sale(id),
  CONSTRAINT ck_refund_status CHECK (status IN ('PENDING','FAILED','SUCCESS')),
  CONSTRAINT ck_refund_amount CHECK (amount_cents BETWEEN 1 AND 10000000000),
  CONSTRAINT ck_refund_success CHECK (status <> 'SUCCESS' OR success_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE review (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_item_id BIGINT NOT NULL,
  stars TINYINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_review_item (order_item_id),
  CONSTRAINT fk_review_item FOREIGN KEY (order_item_id) REFERENCES order_item(id),
  CONSTRAINT ck_review_stars CHECK (stars BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
