-- Order Service — initial schema
-- Matches Order, OrderLineItem, ShippingAddress (@Embedded) @Entity definitions

CREATE TABLE orders (
    id                  VARCHAR(36)    NOT NULL,
    customer_id         VARCHAR(36)    NOT NULL,
    checkout_session_id VARCHAR(36),
    status              VARCHAR(20)    NOT NULL,
    subtotal            DECIMAL(10,2)  NOT NULL,
    shipping_cost       DECIMAL(10,2)  NOT NULL,
    total               DECIMAL(10,2)  NOT NULL,
    cancellation_reason VARCHAR(500),
    -- ShippingAddress embedded fields
    full_name           VARCHAR(255),
    address_line1       VARCHAR(255),
    address_line2       VARCHAR(255),
    city                VARCHAR(255),
    state               VARCHAR(255),
    postal_code         VARCHAR(255),
    country             VARCHAR(255),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_order_customer   (customer_id),
    INDEX idx_order_status     (status),
    INDEX idx_order_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_line_items (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    order_id     VARCHAR(36)    NOT NULL,
    product_id   VARCHAR(36)    NOT NULL,
    product_name VARCHAR(255)   NOT NULL,
    quantity     INT            NOT NULL,
    unit_price   DECIMAL(10,2)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_oli_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
