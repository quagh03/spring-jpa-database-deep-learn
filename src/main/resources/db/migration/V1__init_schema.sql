-- =====================================================================
-- V1: SCHEMA (DDL). Flyway chạy đúng 1 lần, được version-control & checksum.
-- Cố tình KHÔNG tạo index tối ưu (chỉ PK + UNIQUE) — Phase 4 bạn tự thêm.
-- Khi thêm index sau này: tạo file mới V3__add_xxx_index.sql (đừng sửa V1).
-- =====================================================================

CREATE TABLE users (
                       id          BIGSERIAL PRIMARY KEY,
                       email       VARCHAR(255) NOT NULL UNIQUE,
                       full_name   VARCHAR(255) NOT NULL,
                       created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE addresses (
                           id          BIGSERIAL PRIMARY KEY,
                           user_id     BIGINT NOT NULL REFERENCES users(id),
                           line1       VARCHAR(255) NOT NULL,
                           city        VARCHAR(100) NOT NULL,
                           country     VARCHAR(100) NOT NULL,
                           is_default  BOOLEAN NOT NULL DEFAULT false
);

-- Tự tham chiếu: cây danh mục
CREATE TABLE categories (
                            id          BIGSERIAL PRIMARY KEY,
                            parent_id   BIGINT REFERENCES categories(id),
                            name        VARCHAR(150) NOT NULL,
                            slug        VARCHAR(150) NOT NULL UNIQUE
);

CREATE TABLE products (
                          id          BIGSERIAL PRIMARY KEY,
                          category_id BIGINT NOT NULL REFERENCES categories(id),
                          sku         VARCHAR(64) NOT NULL UNIQUE,
                          name        VARCHAR(255) NOT NULL,
                          price       NUMERIC(12,2) NOT NULL,
                          status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / INACTIVE / OUT_OF_STOCK
                          created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_images (
                                id          BIGSERIAL PRIMARY KEY,
                                product_id  BIGINT NOT NULL REFERENCES products(id),
                                url         VARCHAR(500) NOT NULL,
                                sort_order  INT NOT NULL DEFAULT 0
);

CREATE TABLE tags (
                      id   BIGSERIAL PRIMARY KEY,
                      name VARCHAR(100) NOT NULL UNIQUE
);

-- ManyToMany join table
CREATE TABLE product_tags (
                              product_id BIGINT NOT NULL REFERENCES products(id),
                              tag_id     BIGINT NOT NULL REFERENCES tags(id),
                              PRIMARY KEY (product_id, tag_id)
);

-- "1-1 logic" + cột version cho optimistic lock
CREATE TABLE inventory (
                           product_id  BIGINT PRIMARY KEY REFERENCES products(id),
                           quantity    INT NOT NULL DEFAULT 0,
                           version     BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE orders (
                        id           BIGSERIAL PRIMARY KEY,
                        user_id      BIGINT NOT NULL REFERENCES users(id),
                        status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING/PAID/SHIPPED/CANCELLED
                        total_amount NUMERIC(12,2) NOT NULL DEFAULT 0,         -- denormalize có chủ đích (để 0; tự maintain ở Phase 5)
                        created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
                             id          BIGSERIAL PRIMARY KEY,
                             order_id    BIGINT NOT NULL REFERENCES orders(id),
                             product_id  BIGINT NOT NULL REFERENCES products(id),
                             quantity    INT NOT NULL,
                             unit_price  NUMERIC(12,2) NOT NULL                     -- snapshot giá lúc mua
);

CREATE TABLE payments (
                          id         BIGSERIAL PRIMARY KEY,
                          order_id   BIGINT NOT NULL REFERENCES orders(id),
                          amount     NUMERIC(12,2) NOT NULL,
                          method     VARCHAR(30) NOT NULL,                       -- CARD/COD/WALLET
                          status     VARCHAR(20) NOT NULL DEFAULT 'INIT',
                          created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reviews (
                         id          BIGSERIAL PRIMARY KEY,
                         product_id  BIGINT NOT NULL REFERENCES products(id),
                         user_id     BIGINT NOT NULL REFERENCES users(id),
                         rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
                         comment     TEXT,
                         created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);