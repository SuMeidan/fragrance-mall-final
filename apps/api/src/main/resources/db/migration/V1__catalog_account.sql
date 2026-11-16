-- SCENT formal schema; monetary values are integer CNY cents, all DATETIME values UTC.
CREATE TABLE account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  login_name VARCHAR(32) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(32) NOT NULL,
  role VARCHAR(12) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_account_login (login_name),
  CONSTRAINT ck_account_role CHECK (role IN ('BUYER','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_session (
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_session_account (account_id),
  KEY ix_session_expiry (expires_at),
  CONSTRAINT fk_session_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_address (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  receiver VARCHAR(32) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  region VARCHAR(120) NOT NULL,
  detail VARCHAR(200) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_address_owner (account_id, id),
  CONSTRAINT fk_address_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE category (
  id BIGINT NOT NULL PRIMARY KEY,
  parent_id BIGINT NULL,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(40) NOT NULL,
  input_view VARCHAR(10) NULL,
  sort INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_category_code (code),
  KEY ix_category_parent (parent_id, sort),
  CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category(id),
  CONSTRAINT ck_category_view CHECK (input_view IS NULL OR input_view IN ('SCENT','REFILL','TOOL','GIFT')),
  CONSTRAINT ck_category_level CHECK ((parent_id IS NULL AND input_view IS NULL) OR (parent_id IS NOT NULL AND input_view IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fragrance_family (
  id BIGINT NOT NULL PRIMARY KEY,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(40) NOT NULL,
  description VARCHAR(500) NOT NULL,
  sort INT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_family_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  category_id BIGINT NOT NULL,
  family_id BIGINT NULL,
  name VARCHAR(100) NOT NULL,
  subtitle VARCHAR(160) NOT NULL DEFAULT '',
  spec VARCHAR(80) NOT NULL,
  unit VARCHAR(16) NOT NULL,
  price_cents BIGINT NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  version BIGINT NOT NULL DEFAULT 0,
  image_path VARCHAR(240) NOT NULL,
  description VARCHAR(4000) NOT NULL,
  usage_notes VARCHAR(1500) NOT NULL DEFAULT '',
  material VARCHAR(200) NOT NULL DEFAULT '',
  power_info VARCHAR(200) NOT NULL DEFAULT '',
  contents VARCHAR(1500) NOT NULL DEFAULT '',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_product_category (category_id, active, id),
  KEY ix_product_family (family_id, active, id),
  KEY ix_product_listing (active, id),
  CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id),
  CONSTRAINT fk_product_family FOREIGN KEY (family_id) REFERENCES fragrance_family(id),
  CONSTRAINT ck_product_price CHECK (price_cents BETWEEN 1 AND 100000000),
  CONSTRAINT ck_product_stock CHECK (stock BETWEEN 0 AND 1000000),
  CONSTRAINT ck_product_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_compatibility (
  product_id BIGINT NOT NULL,
  related_product_id BIGINT NOT NULL,
  PRIMARY KEY (product_id, related_product_id),
  KEY ix_compat_related (related_product_id, product_id),
  CONSTRAINT fk_compat_product FOREIGN KEY (product_id) REFERENCES product(id),
  CONSTRAINT fk_compat_related FOREIGN KEY (related_product_id) REFERENCES product(id),
  CONSTRAINT ck_compat_canonical CHECK (product_id < related_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_id BIGINT NULL,
  action VARCHAR(40) NOT NULL,
  resource_type VARCHAR(24) NOT NULL,
  resource_id BIGINT NULL,
  trace_id VARCHAR(36) NOT NULL,
  summary VARCHAR(300) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_audit_created (created_at, id),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
