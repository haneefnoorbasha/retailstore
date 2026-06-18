-- Catalog Service — initial schema
-- Matches Product and Tag @Entity definitions

CREATE TABLE tags (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(60)  NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tag_name (name),
    INDEX idx_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE products (
    id             VARCHAR(36)    NOT NULL,
    name           VARCHAR(255)   NOT NULL,
    description    TEXT,
    price          DECIMAL(10,2)  NOT NULL,
    stock_quantity INT            NOT NULL DEFAULT 0,
    active         TINYINT(1)     NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    INDEX idx_product_price (price),
    INDEX idx_product_name  (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_tags (
    product_id VARCHAR(36) NOT NULL,
    tag_id     BIGINT      NOT NULL,
    PRIMARY KEY (product_id, tag_id),
    CONSTRAINT fk_pt_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_pt_tag     FOREIGN KEY (tag_id)     REFERENCES tags     (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
