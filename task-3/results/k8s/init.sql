CREATE TABLE IF NOT EXISTS shipments (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO shipments (client_id, driver_id, vehicle_id, status, created_at) VALUES
(101, 1001, 501, 'delivered', now() - interval '2 hours'),
(102, 1002, 502, 'in_transit', now() - interval '1 hour'),
(103, 1003, 503, 'created', now());
