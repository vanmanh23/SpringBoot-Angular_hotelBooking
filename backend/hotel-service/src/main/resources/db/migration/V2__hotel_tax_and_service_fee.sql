-- Per-hotel pricing rates used by booking-service (DECISIONS D-020). Snapshotted on each booking.
ALTER TABLE hotels
    ADD COLUMN tax_rate_percent    NUMERIC(5, 2) NOT NULL DEFAULT 8.00,
    ADD COLUMN service_fee_percent NUMERIC(5, 2) NOT NULL DEFAULT 5.00,
    ADD CONSTRAINT chk_hotels_tax_rate CHECK (tax_rate_percent BETWEEN 0 AND 30),
    ADD CONSTRAINT chk_hotels_service_fee CHECK (service_fee_percent BETWEEN 0 AND 30);
