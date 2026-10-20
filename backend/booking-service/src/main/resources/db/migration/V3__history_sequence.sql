-- History entries written in the same instant (create + immediate confirmation) need a deterministic order.
-- Existing rows get numbers in table order; ties are only possible within the same created_at anyway.
ALTER TABLE booking_status_history ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
DROP INDEX idx_booking_history_booking;
CREATE INDEX idx_booking_history_booking ON booking_status_history (booking_id, created_at, seq);
