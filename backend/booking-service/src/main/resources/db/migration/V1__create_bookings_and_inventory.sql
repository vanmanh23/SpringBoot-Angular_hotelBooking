-- Design ref: hotel-stayed-report.docx 4.4.5 (bookings), 4.4.6 (booking_rooms), 4.8 (concurrency), 4.9.1 (transaction)
-- hotel_id / room_type_id / user_id are logical references to other services (no cross-database FKs, 4.10).

CREATE TABLE bookings (
    id                  UUID          PRIMARY KEY,
    booking_code        VARCHAR(30)   NOT NULL,
    user_id             UUID          NOT NULL,
    hotel_id            UUID          NOT NULL,
    hotel_name          VARCHAR(200)  NOT NULL,          -- snapshot
    hotel_time_zone     VARCHAR(50)   NOT NULL,          -- snapshot, drives cancellation/completion rules
    check_in_time       TIME          NOT NULL,          -- snapshot (hotel-local)
    check_in            DATE          NOT NULL,
    check_out           DATE          NOT NULL,
    nights              SMALLINT      NOT NULL,
    adults              SMALLINT      NOT NULL,
    children            SMALLINT      NOT NULL,
    status              VARCHAR(30)   NOT NULL,
    subtotal_amount     NUMERIC(14, 2) NOT NULL,
    service_fee_percent NUMERIC(5, 2) NOT NULL,
    fee_amount          NUMERIC(14, 2) NOT NULL,
    tax_rate_percent    NUMERIC(5, 2) NOT NULL,
    tax_amount          NUMERIC(14, 2) NOT NULL,
    discount_amount     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(14, 2) NOT NULL,
    currency_code       CHAR(3)       NOT NULL,
    contact_name        VARCHAR(150)  NOT NULL,
    contact_email       VARCHAR(255)  NOT NULL,
    contact_phone       VARCHAR(30),
    special_requests    VARCHAR(1000),
    expires_at          TIMESTAMPTZ,
    confirmed_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancel_reason       VARCHAR(300),
    refund_eligible     BOOLEAN,
    idempotency_key     VARCHAR(100)  NOT NULL,
    request_hash        CHAR(64)      NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_bookings_code UNIQUE (booking_code),
    CONSTRAINT uq_bookings_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT chk_bookings_dates CHECK (check_out > check_in),
    CONSTRAINT chk_bookings_nights CHECK (nights = check_out - check_in AND nights BETWEEN 1 AND 30),
    CONSTRAINT chk_bookings_guests CHECK (adults >= 1 AND children >= 0),
    CONSTRAINT chk_bookings_status CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'COMPLETED')),
    CONSTRAINT chk_bookings_amounts CHECK (subtotal_amount > 0 AND fee_amount >= 0 AND tax_amount >= 0
        AND discount_amount >= 0 AND total_amount = subtotal_amount + fee_amount + tax_amount - discount_amount),
    CONSTRAINT chk_bookings_currency CHECK (currency_code = 'VND'),
    CONSTRAINT chk_bookings_pending_has_expiry CHECK (status <> 'PENDING_PAYMENT' OR expires_at IS NOT NULL)
);
-- "My bookings" (4.7)
CREATE INDEX idx_bookings_user_created ON bookings (user_id, created_at DESC);
-- Expiry job: only pending holds matter (partial index, 4.7)
CREATE INDEX idx_bookings_pending_expiry ON bookings (expires_at) WHERE status = 'PENDING_PAYMENT';
-- Completion job and hotel views
CREATE INDEX idx_bookings_confirmed_checkout ON bookings (check_out) WHERE status = 'CONFIRMED';
CREATE INDEX idx_bookings_hotel_checkin ON bookings (hotel_id, check_in);

CREATE TABLE booking_rooms (
    id             UUID          PRIMARY KEY,
    booking_id     UUID          NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    room_type_id   UUID          NOT NULL,
    room_type_name VARCHAR(150)  NOT NULL,           -- snapshot
    quantity       SMALLINT      NOT NULL,
    unit_price     NUMERIC(12, 2) NOT NULL,          -- snapshot of base price per room per night
    nights         SMALLINT      NOT NULL,
    line_subtotal  NUMERIC(14, 2) NOT NULL,
    CONSTRAINT uq_booking_rooms_type UNIQUE (booking_id, room_type_id),
    CONSTRAINT chk_booking_rooms_quantity CHECK (quantity BETWEEN 1 AND 10),
    CONSTRAINT chk_booking_rooms_amounts CHECK (unit_price > 0 AND line_subtotal = unit_price * quantity * nights)
);
CREATE INDEX idx_booking_rooms_booking ON booking_rooms (booking_id);

CREATE TABLE booking_status_history (
    id          UUID         PRIMARY KEY,
    booking_id  UUID         NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    from_status VARCHAR(30),
    to_status   VARCHAR(30)  NOT NULL,
    reason      VARCHAR(300),
    actor       VARCHAR(60)  NOT NULL,                -- user id or SYSTEM
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_booking_history_booking ON booking_status_history (booking_id, created_at);

-- Reserved units per room type and night (P-01 / D-018). A row exists once any booking touched that night.
-- Capacity (total units) is NOT stored here: it is read from hotel-service and enforced by a conditional UPDATE.
CREATE TABLE room_type_inventory (
    room_type_id   UUID    NOT NULL,
    stay_date      DATE    NOT NULL,
    reserved_units INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (room_type_id, stay_date),
    CONSTRAINT chk_inventory_non_negative CHECK (reserved_units >= 0)
);
