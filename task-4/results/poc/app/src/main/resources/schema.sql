CREATE TABLE IF NOT EXISTS inventory_balance (
    sku VARCHAR(64) NOT NULL,
    warehouse_code VARCHAR(32) NOT NULL,
    quantity INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    source_file VARCHAR(255) NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sku, warehouse_code)
);
